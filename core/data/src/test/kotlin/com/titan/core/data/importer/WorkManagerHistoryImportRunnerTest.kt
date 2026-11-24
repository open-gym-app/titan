package com.titan.core.data.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportRun
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The runner over a test WorkManager: one unique import at a time, what its record says read back
 * as the screen's state, and the record forgotten once the user has read it.
 *
 * The worker itself is `HistoryImportWorkerTest`'s subject. Here a stand-in takes its place — one
 * that runs until cancelled, or one that finishes at once with a report — so what is under test is
 * only the scheduling around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WorkManagerHistoryImportRunnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)
    private val watchers = HistoryImportWatchers()
    private val runner = WorkManagerHistoryImportRunner(
        context,
        HistoryImportNotifications(context),
        watchers,
        Dispatchers.Unconfined,
    )
    private val request = HistoryImportRequest(HistoryImportSource.STRONG, "content://org.example.documents/document-1")

    @Before
    fun initialiseWorkManager() {
        finishes = false
        publishes = false
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(StandInFactory)
                .build(),
        )
    }

    /**
     * Stops the stand-in a test left waiting, then closes the database `initialiseWorkManager`
     * opened: otherwise every test leaves both behind for the rest of the JVM.
     */
    @After
    fun closeWorkManager() {
        workManager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `with no import there is nothing to show`() =
        runBlocking {
            assertEquals(HistoryImportRun.None, runner.observe().first())
        }

    /** How the worker knows the import screen is open, and leaves the screen to say how it ended. */
    @Test
    fun `whoever is observing counts as watching, for as long as they observe`() =
        runBlocking {
            val screen = launch(start = CoroutineStart.UNDISPATCHED) { runner.observe().collect() }
            val watchedWhileObserved = watchers.anyone
            screen.cancelAndJoin()

            assertEquals(true, watchedWhileObserved)
            assertEquals(false, watchers.anyone)
        }

    @Test
    fun `starting an import runs it under the unique name, carrying its request`() =
        runBlocking {
            runner.start(request)

            assertEquals(HistoryImportRun.Running(request, HistoryImportProgress.Reading), current())
            assertEquals(1, imports().size)
        }

    @Test
    fun `a second start while one runs starts nothing`() =
        runBlocking {
            runner.start(request)
            val first = imports().single().id

            runner.start(request.copy(source = HistoryImportSource.HEVY))

            assertEquals(listOf(first), imports().map(WorkInfo::id))
        }

    @Test
    fun `cancelling a running import reports it cancelled`() =
        runBlocking {
            runner.start(request)

            runner.cancel()

            assertEquals(HistoryImportRun.Cancelled(request), current())
        }

    @Test
    fun `a finished import reports what it wrote until it is dismissed`() =
        runBlocking {
            finishes = true
            runner.start(request)

            assertEquals(HistoryImportRun.Completed(request, REPORT), current())

            runner.dismiss()

            assertEquals(HistoryImportRun.None, current())
        }

    /**
     * A `RUNNING` record that has published a phase, with no import worker started in this
     * process behind it, is what a process that died leaves. The stand-in publishing and then
     * waiting is that record; dismissing it has to remove it, though pruning skips unfinished work.
     */
    @Test
    fun `an import a dead process left running is interrupted, and dismissing it forgets it`() =
        runBlocking {
            publishes = true
            runner.start(request)

            assertEquals(HistoryImportRun.Interrupted(request), current())

            runner.dismiss()

            assertEquals(HistoryImportRun.None, current())
        }

    @Test
    fun `a new import replaces a finished one`() =
        runBlocking {
            finishes = true
            runner.start(request)
            finishes = false

            runner.start(request.copy(source = HistoryImportSource.JEFIT))

            assertEquals(
                HistoryImportRun.Running(
                    request.copy(source = HistoryImportSource.JEFIT),
                    HistoryImportProgress.Reading,
                ),
                current(),
            )
        }

    /**
     * What the runner reports right now. Read rather than waited for: every executor here is
     * synchronous, so each call above has landed in WorkManager's records — and the stand-in has
     * run as far as it goes — by the time it returns. A wait with a deadline would only measure
     * how busy the machine is, and fail a run on a loaded one.
     */
    private suspend fun current(): HistoryImportRun = runner.observe().first()

    private fun imports(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(HistoryImportWork.WORK_NAME).get().filterNot { it.state.isFinished }

    /**
     * Stands in for `HistoryImportWorker`: runs until cancelled, or finishes with [REPORT]. Not
     * the real worker, so its record never counts as started in this process.
     */
    class StandInWorker(
        context: Context,
        parameters: WorkerParameters,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            if (finishes) return Result.success(HistoryImportWork.reportOutput(REPORT))
            if (publishes) setProgress(HistoryImportWork.progressData(HistoryImportProgress.Writing(1, 2)))
            awaitCancellation()
        }
    }

    private object StandInFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = StandInWorker(appContext, workerParameters)
    }

    companion object {

        /** Whether the stand-in finishes at once. Read when WorkManager runs it. */
        @Volatile
        private var finishes = false

        /** Whether the stand-in publishes a phase before it waits, as a real import does. */
        @Volatile
        private var publishes = false

        private val REPORT = HistoryImportReport(
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
