package com.titan.core.data.importer

import androidx.work.Data
import androidx.work.WorkInfo
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportRun
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Both ends of what the import worker writes into WorkManager and the screen reads back.
 *
 * The worker and the screen share no memory — the screen may open in a process started after the
 * worker's ended — so a value that does not survive the round trip is a report the user never
 * sees. The second half is what each WorkManager state means to the screen, which is where an
 * import killed with its process becomes an offer to run it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HistoryImportWorkTest {

    private val request = HistoryImportRequest(
        HistoryImportSource.FITNOTES,
        "content://org.example.documents/document-1",
    )

    @Test
    fun `the request survives the tags and the input`() {
        assertEquals(request, HistoryImportWork.requestOf(HistoryImportWork.tagsOf(request)))
        assertEquals(request, HistoryImportWork.requestOf(HistoryImportWork.inputOf(request)))
    }

    @Test
    fun `tags or input that are not an import's carry no request`() {
        assertNull(HistoryImportWork.requestOf(setOf("auto-backup")))
        assertNull(HistoryImportWork.requestOf(setOf("history-import-source:NOT_AN_APP", "history-import-document:x")))
        assertNull(HistoryImportWork.requestOf(Data.EMPTY))
    }

    @Test
    fun `every running phase survives the progress data`() {
        listOf(
            HistoryImportProgress.Reading,
            HistoryImportProgress.Writing(workoutsWritten = 120, workoutsTotal = 3_400),
            HistoryImportProgress.StoringPhotos(photosStored = 3, photosTotal = 40),
        ).forEach { progress ->
            assertEquals(progress, HistoryImportWork.progressOf(HistoryImportWork.progressData(progress)))
        }
        assertNull(HistoryImportWork.progressOf(Data.EMPTY))
    }

    @Test
    fun `every refusal survives the output`() {
        listOf(
            HistoryImportError.NotRecognised,
            HistoryImportError.Malformed("row 4"),
            HistoryImportError.TooLarge(limitBytes = 33_554_432),
            HistoryImportError.TooManySets(limitSets = 500_000),
            HistoryImportError.Unreadable("revoked"),
            HistoryImportError.NothingToImport,
            HistoryImportError.StorageFailure("disk full"),
        ).forEach { error ->
            assertEquals(error, HistoryImportWork.errorOf(HistoryImportWork.failureOutput(error)))
        }
    }

    @Test
    fun `a reason is kept short, since it is for a log`() {
        val output = HistoryImportWork.failureOutput(HistoryImportError.Malformed("x".repeat(5_000)))

        assertEquals(500, (HistoryImportWork.errorOf(output) as HistoryImportError.Malformed).reason.length)
    }

    @Test
    fun `an output naming no refusal still reads as nothing imported`() {
        assertEquals(HistoryImportError.StorageFailure(""), HistoryImportWork.errorOf(Data.EMPTY))
    }

    @Test
    fun `the whole report survives the output`() {
        assertEquals(REPORT, HistoryImportWork.reportOf(HistoryImportWork.reportOutput(REPORT)))
    }

    @Test
    fun `no work is nothing to show`() {
        assertEquals(HistoryImportRun.None, HistoryImportWork.runOf(null, runsHere = false))
        assertEquals(
            HistoryImportRun.None,
            HistoryImportWork.runOf(info(WorkInfo.State.SUCCEEDED, tags = setOf("x")), false),
        )
    }

    @Test
    fun `queued work is an import about to read`() {
        assertEquals(
            HistoryImportRun.Running(request, HistoryImportProgress.Reading),
            HistoryImportWork.runOf(info(WorkInfo.State.ENQUEUED), runsHere = false),
        )
    }

    @Test
    fun `running work reports the phase it published`() {
        val writing = HistoryImportProgress.Writing(workoutsWritten = 5, workoutsTotal = 10)

        assertEquals(
            HistoryImportRun.Running(request, writing),
            HistoryImportWork.runOf(
                info(WorkInfo.State.RUNNING, progress = HistoryImportWork.progressData(writing)),
                true,
            ),
        )
        // Before the worker has published anything, it is reading — wherever it runs.
        assertEquals(
            HistoryImportRun.Running(request, HistoryImportProgress.Reading),
            HistoryImportWork.runOf(info(WorkInfo.State.RUNNING), runsHere = false),
        )
    }

    /**
     * WorkManager writes `RUNNING` and nothing rewrites it when the process dies. A record that
     * published a phase, with no worker in this process to have published it, is an import whose
     * transaction died with its process.
     */
    @Test
    fun `running work with no worker here is an import that died with its process`() {
        val writing = HistoryImportWork.progressData(HistoryImportProgress.Writing(5, 10))

        assertEquals(
            HistoryImportRun.Interrupted(request),
            HistoryImportWork.runOf(info(WorkInfo.State.RUNNING, progress = writing), runsHere = false),
        )
    }

    @Test
    fun `each finished state means what it says`() {
        assertEquals(
            HistoryImportRun.Completed(request, REPORT),
            HistoryImportWork.runOf(
                info(WorkInfo.State.SUCCEEDED, output = HistoryImportWork.reportOutput(REPORT)),
                false,
            ),
        )
        assertEquals(
            HistoryImportRun.Failed(request, HistoryImportError.NothingToImport),
            HistoryImportWork.runOf(
                info(
                    WorkInfo.State.FAILED,
                    output = HistoryImportWork.failureOutput(HistoryImportError.NothingToImport),
                ),
                false,
            ),
        )
        assertEquals(
            HistoryImportRun.Interrupted(request),
            HistoryImportWork.runOf(info(WorkInfo.State.FAILED, output = HistoryImportWork.interruptedOutput()), false),
        )
        assertEquals(
            HistoryImportRun.Cancelled(request),
            HistoryImportWork.runOf(info(WorkInfo.State.CANCELLED), false),
        )
    }

    private fun info(
        state: WorkInfo.State,
        tags: Set<String> = HistoryImportWork.tagsOf(request),
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ) = WorkInfo(UUID.randomUUID(), state, tags, output, progress)

    private companion object {
        val REPORT = HistoryImportReport(
            workoutsImported = 312,
            setsImported = 9_840,
            exercisesMatched = 41,
            exercisesCreated = 6,
            templatesImported = 4,
            measurementsImported = 88,
            photosImported = 12,
            photosFailed = 2,
            skipped = ImportSkips(
                duplicateWorkouts = 1,
                invalidWorkouts = 2,
                invalidSets = 3,
                duplicateTemplates = 4,
                invalidTemplates = 5,
                duplicateMeasurements = 6,
                invalidMeasurements = 7,
                duplicatePhotos = 8,
                invalidPhotos = 9,
            ),
            recordsSet = 140,
            xpEarned = 98_765_432_100L,
            achievementsUnlocked = 11,
            questsCompleted = 23,
            collectiblesFound = 7,
        )
    }
}
