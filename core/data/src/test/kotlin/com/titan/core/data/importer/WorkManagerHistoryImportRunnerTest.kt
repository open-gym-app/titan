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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val runner = WorkManagerHistoryImportRunner(
        context,
        HistoryImportNotifications(context),
        Dispatchers.Unconfined,
    )
    private val request = HistoryImportRequest(HistoryImportSource.STRONG, "content://org.example.documents/document-1")

    @Before
    fun initialiseWorkManager() {
        finishes = false
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(StandInFactory)
                .build(),
        )
    }

    @Test
    fun `with no import there is nothing to show`() =
        runBlocking {
            assertEquals(HistoryImportRun.None, runner.observe().first())
        }

    @Test
    fun `starting an import runs it under the unique name, carrying its request`() =
        runBlocking {
            runner.start(request)

            assertEquals(
                HistoryImportRun.Running(request, HistoryImportProgress.Reading),
                awaitRun<HistoryImportRun.Running>(),
            )
            assertEquals(
                workManager.getWorkInfosForUniqueWork(HistoryImportWork.WORK_NAME).get().toString(),
                1,
                imports().size,
            )
        }

    @Test
    fun `a second start while one runs starts nothing`() =
        runBlocking {
            runner.start(request)
            awaitRun<HistoryImportRun.Running>()
            val first = imports().single().id

            runner.start(request.copy(source = HistoryImportSource.HEVY))

            assertEquals(listOf(first), imports().map(WorkInfo::id))
        }

    @Test
    fun `cancelling a running import reports it cancelled`() =
        runBlocking {
            runner.start(request)
            awaitRun<HistoryImportRun.Running>()

            runner.cancel()

            assertEquals(HistoryImportRun.Cancelled(request), awaitRun<HistoryImportRun.Cancelled>())
        }

    @Test
    fun `a finished import reports what it wrote until it is dismissed`() =
        runBlocking {
            finishes = true
            runner.start(request)

            assertEquals(HistoryImportRun.Completed(request, REPORT), awaitRun<HistoryImportRun.Completed>())

            runner.dismiss()

            assertEquals(HistoryImportRun.None, awaitRun<HistoryImportRun.None>())
        }

    @Test
    fun `a new import replaces a finished one`() =
        runBlocking {
            finishes = true
            runner.start(request)
            awaitRun<HistoryImportRun.Completed>()
            finishes = false

            runner.start(request.copy(source = HistoryImportSource.JEFIT))

            assertEquals(
                HistoryImportRun.Running(
                    request.copy(source = HistoryImportSource.JEFIT),
                    HistoryImportProgress.Reading,
                ),
                awaitRun<HistoryImportRun.Running>(),
            )
        }

    private suspend inline fun <reified T : HistoryImportRun> awaitRun(): HistoryImportRun =
        withTimeout(TIMEOUT_MS) { runner.observe().first { it is T } }

    private fun imports(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(HistoryImportWork.WORK_NAME).get().filterNot { it.state.isFinished }

    /** Stands in for `HistoryImportWorker`: runs until cancelled, or finishes with [REPORT]. */
    class StandInWorker(
        context: Context,
        parameters: WorkerParameters,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            if (finishes) return Result.success(HistoryImportWork.reportOutput(REPORT))
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

        /** Whether the stand-in finishes at once. Read from WorkManager's thread. */
        @Volatile
        private var finishes = false

        private const val TIMEOUT_MS = 5_000L

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
