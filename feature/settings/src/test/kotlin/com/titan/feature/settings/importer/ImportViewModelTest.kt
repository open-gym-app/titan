package com.titan.feature.settings.importer

import androidx.lifecycle.SavedStateHandle
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportRun
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips
import com.titan.feature.settings.afterProcessDeath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The import screen's ViewModel: what it draws for each thing the worker says, and what it asks
 * the worker to do.
 *
 * The claim that matters most is the one the class doc makes: the ViewModel never runs the import,
 * it starts it and observes it — so every outcome here is reached by setting what the runner
 * reports, never by anything this class collects to the end. The second is the question about a
 * workout in progress: an import holds every database write behind it, so it never starts over an
 * open workout without the user saying so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val runner = FakeHistoryImportRunner()
    private val sessions = FakeActiveSessions()
    private var savedState = SavedStateHandle()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    // --- before an import -----------------------------------------------------

    @Test
    fun `with nothing running the screen opens on the list of apps`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            assertEquals(ImportStage.ChooseSource, viewModel.uiState.value.stage)
        }

    @Test
    fun `choosing an app opens its guide, and back returns to the list`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(ImportEvent.SourceChosen(HistoryImportSource.FITNOTES))
            assertEquals(ImportStage.Guide(HistoryImportSource.FITNOTES), viewModel.uiState.value.stage)

            viewModel.onEvent(ImportEvent.GuideClosed)
            assertEquals(ImportStage.ChooseSource, viewModel.uiState.value.stage)
        }

    @Test
    fun `a picked file starts the import for the app whose guide it came from`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(ImportEvent.SourceChosen(HistoryImportSource.STRONG))

            viewModel.onEvent(ImportEvent.FileChosen(DOCUMENT))

            assertEquals(listOf(HistoryImportRequest(HistoryImportSource.STRONG, DOCUMENT)), runner.started)
            assertEquals(
                ImportStage.Running(HistoryImportSource.STRONG, HistoryImportProgress.Reading),
                viewModel.uiState.value.stage,
            )
        }

    @Test
    fun `a location that is not a picked document starts nothing`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(ImportEvent.SourceChosen(HistoryImportSource.STRONG))

            viewModel.onEvent(ImportEvent.FileChosen("file:///data/data/app.titan.gym/databases/titan.db"))

            assertEquals(emptyList<HistoryImportRequest>(), runner.started)
        }

    // --- a workout in progress ------------------------------------------------

    @Test
    fun `a workout in progress is asked about before anything starts`() =
        runTest {
            sessions.active.value = OPEN_WORKOUT
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(ImportEvent.SourceChosen(HistoryImportSource.HEVY))

            viewModel.onEvent(ImportEvent.FileChosen(DOCUMENT))

            assertEquals(emptyList<HistoryImportRequest>(), runner.started)
            assertEquals(
                HistoryImportRequest(HistoryImportSource.HEVY, DOCUMENT),
                viewModel.uiState.value.pendingRequest,
            )
        }

    @Test
    fun `going ahead starts it, and not now starts nothing`() =
        runTest {
            sessions.active.value = OPEN_WORKOUT
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(ImportEvent.SourceChosen(HistoryImportSource.HEVY))

            viewModel.onEvent(ImportEvent.FileChosen(DOCUMENT))
            viewModel.onEvent(ImportEvent.StartDismissed)
            assertEquals(emptyList<HistoryImportRequest>(), runner.started)
            assertNull(viewModel.uiState.value.pendingRequest)

            viewModel.onEvent(ImportEvent.FileChosen(DOCUMENT))
            viewModel.onEvent(ImportEvent.StartConfirmed)
            assertEquals(listOf(HistoryImportRequest(HistoryImportSource.HEVY, DOCUMENT)), runner.started)
            assertNull(viewModel.uiState.value.pendingRequest)
        }

    /** The picked file is a document the picker handed over; a process death must not lose it. */
    @Test
    fun `a file waiting on the question survives a process death`() =
        runTest {
            sessions.active.value = OPEN_WORKOUT
            val first = viewModel()
            collect(first)
            first.onEvent(ImportEvent.SourceChosen(HistoryImportSource.HEVY))
            first.onEvent(ImportEvent.FileChosen(DOCUMENT))

            savedState = savedState.afterProcessDeath()
            val restored = viewModel()
            collect(restored)

            assertEquals(
                HistoryImportRequest(HistoryImportSource.HEVY, DOCUMENT),
                restored.uiState.value.pendingRequest,
            )
            assertEquals(ImportStage.Guide(HistoryImportSource.HEVY), restored.uiState.value.stage)
        }

    // --- while it runs ---------------------------------------------------------

    @Test
    fun `each phase the worker reports is the stage drawn`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            listOf(
                HistoryImportProgress.Reading,
                HistoryImportProgress.Writing(workoutsWritten = 3, workoutsTotal = 10),
                HistoryImportProgress.StoringPhotos(photosStored = 1, photosTotal = 4),
            ).forEach { progress ->
                runner.run.value = HistoryImportRun.Running(REQUEST, progress)

                assertEquals(ImportStage.Running(HistoryImportSource.STRONG, progress), viewModel.uiState.value.stage)
            }
        }

    @Test
    fun `cancelling asks first, and only a confirmed cancel reaches the worker`() =
        runTest {
            runner.run.value = HistoryImportRun.Running(REQUEST, HistoryImportProgress.Writing(3, 10))
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(ImportEvent.CancelRequested)
            assertTrue(viewModel.uiState.value.confirmingCancel)
            viewModel.onEvent(ImportEvent.CancelDismissed)
            assertFalse(viewModel.uiState.value.confirmingCancel)
            assertEquals(0, runner.cancels)

            viewModel.onEvent(ImportEvent.CancelRequested)
            viewModel.onEvent(ImportEvent.CancelConfirmed)
            assertEquals(1, runner.cancels)
            assertFalse(viewModel.uiState.value.confirmingCancel)
        }

    @Test
    fun `a cancel while writing reads as nothing imported`() =
        runTest {
            runner.run.value = HistoryImportRun.Running(REQUEST, HistoryImportProgress.Writing(3, 10))
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(ImportEvent.CancelRequested)
            viewModel.onEvent(ImportEvent.CancelConfirmed)
            runner.run.value = HistoryImportRun.Cancelled(REQUEST)

            assertEquals(
                ImportStage.Stopped(HistoryImportSource.STRONG, ImportStop.NOTHING_IMPORTED),
                viewModel.uiState.value.stage,
            )
        }

    /** By the photos the history has committed, so stopping then costs the photos and nothing else. */
    @Test
    fun `a cancel while storing photos reads as photos skipped`() =
        runTest {
            runner.run.value = HistoryImportRun.Running(REQUEST, HistoryImportProgress.StoringPhotos(2, 9))
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(ImportEvent.CancelRequested)
            viewModel.onEvent(ImportEvent.CancelConfirmed)
            runner.run.value = HistoryImportRun.Cancelled(REQUEST)

            assertEquals(
                ImportStage.Stopped(HistoryImportSource.STRONG, ImportStop.PHOTOS_SKIPPED),
                viewModel.uiState.value.stage,
            )
        }

    @Test
    fun `a cancel this screen did not see is said in words true either way`() =
        runTest {
            runner.run.value = HistoryImportRun.Cancelled(REQUEST)
            val viewModel = viewModel()
            collect(viewModel)

            assertEquals(
                ImportStage.Stopped(HistoryImportSource.STRONG, ImportStop.UNKNOWN),
                viewModel.uiState.value.stage,
            )
        }

    @Test
    fun `counts only move forward within a phase`() =
        runTest {
            val emitted = flowOf(
                running(HistoryImportProgress.Writing(5, 10)),
                running(HistoryImportProgress.Writing(3, 10)),
                running(HistoryImportProgress.Writing(8, 10)),
                running(HistoryImportProgress.StoringPhotos(4, 5)),
                running(HistoryImportProgress.StoringPhotos(1, 5)),
            ).forwardOnly().toList()

            assertEquals(
                listOf(5, 5, 8),
                emitted.take(
                    3,
                ).map { ((it as HistoryImportRun.Running).progress as HistoryImportProgress.Writing).workoutsWritten },
            )
            assertEquals(
                listOf(4, 4),
                emitted.drop(
                    3,
                ).map {
                    ((it as HistoryImportRun.Running).progress as HistoryImportProgress.StoringPhotos).photosStored
                },
            )
        }

    @Test
    fun `a new import starts its counts afresh`() {
        val previous = running(HistoryImportProgress.Writing(9, 10))
        val next = HistoryImportRun.Running(
            REQUEST.copy(document = "content://other/2"),
            HistoryImportProgress.Writing(1, 4),
        )

        assertEquals(next, next.notBehind(previous))
    }

    // --- how it ended ----------------------------------------------------------

    @Test
    fun `a finished import is its report until it is dismissed`() =
        runTest {
            runner.run.value = HistoryImportRun.Completed(REQUEST, REPORT)
            val viewModel = viewModel()
            collect(viewModel)

            assertEquals(ImportStage.Completed(HistoryImportSource.STRONG, REPORT), viewModel.uiState.value.stage)

            viewModel.onEvent(ImportEvent.OutcomeDismissed)

            assertEquals(1, runner.dismissals)
            assertEquals(ImportStage.ChooseSource, viewModel.uiState.value.stage)
        }

    @Test
    fun `a refusal offers the same app's guide again, or the list`() =
        runTest {
            runner.run.value = HistoryImportRun.Failed(REQUEST, HistoryImportError.NotRecognised)
            val viewModel = viewModel()
            collect(viewModel)
            assertEquals(
                ImportStage.Failed(HistoryImportSource.STRONG, HistoryImportError.NotRecognised),
                viewModel.uiState.value.stage,
            )

            viewModel.onEvent(ImportEvent.TryAgain)
            assertEquals(ImportStage.Guide(HistoryImportSource.STRONG), viewModel.uiState.value.stage)

            runner.run.value = HistoryImportRun.Failed(REQUEST, HistoryImportError.NothingToImport)
            viewModel.onEvent(ImportEvent.ChooseAnotherApp)
            assertEquals(ImportStage.ChooseSource, viewModel.uiState.value.stage)
            assertEquals(2, runner.dismissals)
        }

    /** A process killed part way: offered again with the same file, through the same question. */
    @Test
    fun `an interrupted import can be run again as it was`() =
        runTest {
            runner.run.value = HistoryImportRun.Interrupted(REQUEST)
            val viewModel = viewModel()
            collect(viewModel)
            assertEquals(ImportStage.Interrupted(REQUEST), viewModel.uiState.value.stage)

            viewModel.onEvent(ImportEvent.RunAgain)

            assertEquals(listOf(REQUEST), runner.started)
        }

    @Test
    fun `running an interrupted import again over an open workout asks first`() =
        runTest {
            sessions.active.value = OPEN_WORKOUT
            runner.run.value = HistoryImportRun.Interrupted(REQUEST)
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(ImportEvent.RunAgain)

            assertEquals(emptyList<HistoryImportRequest>(), runner.started)
            assertEquals(REQUEST, viewModel.uiState.value.pendingRequest)
        }

    @Test
    fun `an import the worker could not even start is a failure that wrote nothing`() =
        runTest {
            runner.startFailure = IllegalStateException("WorkManager is not initialised")
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(ImportEvent.SourceChosen(HistoryImportSource.JEFIT))

            viewModel.onEvent(ImportEvent.FileChosen(DOCUMENT))

            assertEquals(
                ImportStage.Failed(HistoryImportSource.JEFIT, HistoryImportError.StorageFailure("")),
                viewModel.uiState.value.stage,
            )
        }

    private fun running(progress: HistoryImportProgress) = HistoryImportRun.Running(REQUEST, progress)

    private fun viewModel() =
        ImportViewModel(savedStateHandle = savedState, runner = runner, sessionRepository = sessions)

    private fun TestScope.collect(viewModel: ImportViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
    }

    private companion object {
        const val DOCUMENT = "content://org.example.documents/document-1"

        val REQUEST = HistoryImportRequest(HistoryImportSource.STRONG, DOCUMENT)

        val REPORT = HistoryImportReport(
            workoutsImported = 3,
            setsImported = 30,
            exercisesMatched = 2,
            exercisesCreated = 1,
            templatesImported = 0,
            measurementsImported = 0,
            skipped = ImportSkips(),
            recordsSet = 4,
            xpEarned = 500,
            achievementsUnlocked = 1,
            questsCompleted = 0,
            collectiblesFound = 0,
        )
    }
}
