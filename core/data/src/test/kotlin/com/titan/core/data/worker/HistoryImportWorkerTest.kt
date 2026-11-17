package com.titan.core.data.worker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.TestProgressUpdater
import androidx.work.testing.WorkManagerTestInitHelper
import com.titan.core.data.db.HistoryImportStack
import com.titan.core.data.db.InMemoryUserPreferencesRepository
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.importer.HistoryImportNotifications
import com.titan.core.data.importer.HistoryImportWork
import com.titan.core.data.notification.AppForegroundState
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.HistoryParsers
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

/**
 * The import worker, end to end: a document served by another app's provider, read by the real
 * parser, written by the real engine into a real (Robolectric) database, and reported through
 * what WorkManager keeps.
 *
 * What only the worker decides is what this holds: that a run publishes its phases in order with
 * counts that never go back, that the outcome lands in the output the screen reads, that a
 * re-run after the process died does nothing and says so, and that a document the app may no
 * longer read is "unreadable" rather than "malformed".
 */
class HistoryImportWorkerTest : RoomTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = InMemoryUserPreferencesRepository()
    private val stack by lazy { HistoryImportStack(db, preferences, Clock { NOW }) }
    private val published = mutableListOf<HistoryImportProgress>()
    private var inForeground = false
    private lateinit var documents: ImportDocumentsProvider

    @Before
    fun setUp() {
        // The notification's stop button is a WorkManager pending intent, so there has to be one.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        documents = Robolectric.setupContentProvider(
            ImportDocumentsProvider::class.java,
            ImportDocumentsProvider.AUTHORITY,
        )
    }

    @Test
    fun `a Strong export is imported and its report is the output`() =
        runBlocking {
            val result = run(HistoryImportRequest(HistoryImportSource.STRONG, serve(STRONG_EXPORT)))

            assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
            val report = HistoryImportWork.reportOf(result.outputData)
            assertEquals(2, report.workoutsImported)
            assertEquals(3, report.setsImported)
            assertEquals(2, stack.sessions.getAll().first().size)
        }

    @Test
    fun `the phases are published in order and the counts never go back`() =
        runBlocking {
            run(HistoryImportRequest(HistoryImportSource.STRONG, serve(STRONG_EXPORT)))

            assertEquals(HistoryImportProgress.Reading, published.first())
            val writing = published.filterIsInstance<HistoryImportProgress.Writing>()
            assertTrue("no Writing was published: $published", writing.isNotEmpty())
            assertEquals(writing.map { it.workoutsWritten }.sorted(), writing.map { it.workoutsWritten })
            assertEquals(published.distinct(), published)
        }

    @Test
    fun `a file from another app fails as not recognised and writes nothing`() =
        runBlocking {
            val result = run(HistoryImportRequest(HistoryImportSource.STRONG, serve(HEVY_EXPORT)))

            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(HistoryImportError.NotRecognised, HistoryImportWork.errorOf(result.outputData))
            assertEquals(emptyList<Any>(), stack.sessions.getAll().first())
        }

    /**
     * A re-run after the process died is an import the user did not start, at a moment they did
     * not choose. It reads nothing and reports itself interrupted, which the screen offers again.
     */
    @Test
    fun `a second attempt does nothing and reports the import interrupted`() =
        runBlocking {
            val request = HistoryImportRequest(HistoryImportSource.STRONG, serve(STRONG_EXPORT))

            val result = run(request, runAttemptCount = 1)

            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(true, result.outputData.getBoolean("interrupted", false))
            assertEquals(emptyList<Any>(), stack.sessions.getAll().first())
            assertEquals(emptyList<HistoryImportProgress>(), published)
        }

    @Test
    fun `a document the provider no longer serves is unreadable, not malformed`() =
        runBlocking {
            val result = run(
                HistoryImportRequest(HistoryImportSource.STRONG, "content://${ImportDocumentsProvider.AUTHORITY}/gone"),
            )

            assertTrue(result is ListenableWorker.Result.Failure)
            assertTrue(HistoryImportWork.errorOf(result.outputData) is HistoryImportError.Unreadable)
        }

    @Test
    fun `an import that ends with the app in the background says so in a notification`() =
        runBlocking {
            run(HistoryImportRequest(HistoryImportSource.STRONG, serve(STRONG_EXPORT)))

            val posted = shadowOf(notificationManager()).allNotifications
            assertEquals(1, posted.size)
            assertEquals(HistoryImportNotifications.CHANNEL_ID, posted.single().channelId)
        }

    @Test
    fun `an import that ends on screen leaves the screen to say it`() =
        runBlocking {
            inForeground = true

            run(HistoryImportRequest(HistoryImportSource.STRONG, serve(STRONG_EXPORT)))

            assertEquals(0, shadowOf(notificationManager()).allNotifications.size)
        }

    private fun notificationManager() = context.getSystemService(NotificationManager::class.java)

    private fun serve(text: String): String {
        val file = File(context.cacheDir, "import-${UUID.randomUUID()}.csv").apply { writeText(text) }
        return documents.serve(file).toString()
    }

    private suspend fun run(
        request: HistoryImportRequest,
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result =
        TestListenableWorkerBuilder<HistoryImportWorker>(context)
            .setInputData(HistoryImportWork.inputOf(request))
            .setRunAttemptCount(runAttemptCount)
            .setProgressUpdater(recordingProgress)
            .setForegroundUpdater(TestForegroundUpdater())
            .setWorkerFactory(factory)
            .build()
            .doWork()

    private val recordingProgress = object : ProgressUpdater {
        private val delegate = TestProgressUpdater()

        override fun updateProgress(
            context: Context,
            id: UUID,
            data: Data,
        ) = delegate.updateProgress(context, id, data).also {
            HistoryImportWork.progressOf(data)?.let(published::add)
        }
    }

    private val factory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker =
            HistoryImportWorker(
                appContext = appContext,
                parameters = workerParameters,
                importHistory = stack.importHistory,
                parsers = HistoryParsers.SUPPORTED,
                userPreferencesRepository = preferences,
                notifications = HistoryImportNotifications(appContext),
                foregroundState = object : AppForegroundState {
                    override suspend fun isInForeground(): Boolean = inForeground
                },
                // Unconfined, as in `BackupWorkerTest`: the work is blocking, and handing it the
                // IO pool would only have the test wait on another thread.
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    private companion object {

        /** 2026-11-17, well after the workouts below, so none of them is refused as future. */
        const val NOW = 1_763_337_600_000L

        /** Strong's iOS layout: two workouts, three sets. */
        val STRONG_EXPORT = """
            Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
            2024-03-02 18:04:11,"Push Day",1h 5m,"Bench Press (Barbell)",1,100.0,5.0,0,0.0,,,8
            2024-03-02 18:04:11,"Push Day",1h 5m,"Bench Press (Barbell)",2,100.0,5.0,0,0.0,,,8.5
            2024-03-06 18:00:00,"Legs",1h,"Squat (Barbell)",1,140.0,5.0,0,0.0,,,
        """.trimIndent()

        /** Hevy's app layout, which the Strong parser must refuse. */
        val HEVY_EXPORT = """
            "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
            "Push A","28 Oct 2025, 18:04","28 Oct 2025, 19:06","","Bench Press (Barbell)",,"",0,"normal",80,5,,,8
        """.trimIndent()
    }
}
