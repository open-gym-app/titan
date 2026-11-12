package com.titan.core.data.worker

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.titan.core.data.backup.AutoBackupFiles
import com.titan.core.data.db.CountingInProgressSessionRepository
import com.titan.core.data.db.CountingTemplateDraftRepository
import com.titan.core.data.db.InMemoryAutoBackupRepository
import com.titan.core.data.db.InMemoryUserPreferencesRepository
import com.titan.core.data.db.RecordingBackgroundRestTimer
import com.titan.core.data.db.RecordingIdleSessionMonitor
import com.titan.core.data.db.RecordingReminderScheduler
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.dumpEverything
import com.titan.core.data.db.dumpTable
import com.titan.core.data.db.seedEveryTable
import com.titan.core.data.repository.BackupRepositoryImpl
import com.titan.core.data.repository.SessionStateRepositoryImpl
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportProgress
import com.titan.core.domain.backup.toPayload
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.ImportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The weekly job, end to end: a real export off a real (Robolectric) SQLite, into a real file,
 * read back by the real importer.
 *
 * ### Why the round trip is asserted here rather than trusted
 *
 * #181's acceptance criterion is that a run produces a file `ImportUseCase` can restore, and
 * the only way for that to be true by construction is for the worker to run the same
 * `ExportUseCase` the manual export runs — which it does. What that leaves worth testing is
 * everything around it: that the bytes reach a *complete* file rather than a scratch one, that
 * the file is where the retention rule can find it, and that a failed run leaves nothing a
 * restore could pick up. So the assertion below wipes the database and imports the worker's own
 * output, which fails if any of those is wrong.
 *
 * `TestListenableWorkerBuilder` constructs the worker directly through a factory, so nothing
 * here needs WorkManager to be running — the schedule around it is
 * `WorkManagerAutoBackupSchedulerTest`'s subject.
 */
class BackupWorkerTest : RoomTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val backup by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }
    private val preferences = InMemoryUserPreferencesRepository()
    private val autoBackup = InMemoryAutoBackupRepository()
    private val files = AutoBackupFiles(context)

    /** Moved between runs, so four runs are four days rather than four writes to one file. */
    private var now = FIRST_SUNDAY

    private val export by lazy {
        ExportUseCase(backupRepository = backup, userPreferencesRepository = preferences)
    }

    private val import by lazy {
        ImportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            inProgressSessionRepository = CountingInProgressSessionRepository(),
            templateDraftRepository = CountingTemplateDraftRepository(),
            sessionStateRepository = SessionStateRepositoryImpl(),
            backgroundRestTimer = RecordingBackgroundRestTimer(),
            idleSessionMonitor = RecordingIdleSessionMonitor(),
            reminderScheduler = RecordingReminderScheduler(),
        )
    }

    @Before
    fun emptyTheBackupDirectory() {
        files.directory.deleteRecursively()
    }

    /** The acceptance criterion, whole: the worker's own file restores the device it came from. */
    @Test
    fun `a run writes a document the importer reads back`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            assertEquals(ListenableWorker.Result.success(), run())

            wipeEverything()
            val progress = import(source(backupFile())).toList()

            assertTrue(
                "the import refused the worker's own file: ${progress.last()}",
                progress.last() is ImportProgress.Complete,
            )
            BackupTable.entries.forEach { table ->
                assertEquals(
                    "${table.tableName} did not survive the round trip",
                    before.getValue(table),
                    db.dumpTable(table),
                )
            }
        }

    @Test
    fun `a run names its file for the day and records the success`() =
        runBlocking {
            run()

            assertEquals(listOf("titan-auto-2026-04-26.json"), files.existing().map { it.name })
            assertEquals(FIRST_SUNDAY, autoBackup.current.lastSuccessAt)
            assertNull(autoBackup.current.lastFailureAt)
        }

    /** Nothing is left half-written: a `.tmp` sibling is renamed, not accumulated beside the file. */
    @Test
    fun `a finished run leaves no scratch file behind`() =
        runBlocking {
            run()

            assertEquals(
                emptyList<String>(),
                files.directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") },
            )
        }

    /**
     * Retention, over four weekly runs.
     *
     * Unbounded retention on a file that grows with the user's history is a disk leak on
     * storage they cannot browse to clear, and three weeks is enough to notice a problem and
     * act. The oldest goes; the three newest stay.
     */
    @Test
    fun `a fourth run leaves only the three most recent backups`() =
        runBlocking {
            repeat(RUNS) { week ->
                now = FIRST_SUNDAY + week * WEEK_MILLIS
                run()
            }

            assertEquals(
                listOf(
                    "titan-auto-2026-05-17.json",
                    "titan-auto-2026-05-10.json",
                    "titan-auto-2026-05-03.json",
                ),
                files.existing().map { it.name },
            )
        }

    /** A manual export the user took deliberately is not deleted to make room for a scheduled one. */
    @Test
    fun `retention leaves the manual exports alone`() =
        runBlocking {
            files.directory.mkdirs()
            val manual = File(files.directory, "titan-backup-2026-01-01-0900.json").apply { writeText("{}") }

            repeat(RUNS) { week ->
                now = FIRST_SUNDAY + week * WEEK_MILLIS
                run()
            }

            assertTrue("a manual export was pruned", manual.exists())
        }

    /**
     * Work outlives the flag that asked for it.
     *
     * A schedule cancelled while the process was dead is cancelled eventually, and until then
     * the job may still fire. The stored flag is the authority on whether files are written,
     * not whatever the job scheduler happens to still hold — and a run that declines is a
     * success, since nothing is wrong and a failure would burn a retry and mark the row broken.
     */
    @Test
    fun `a run with the toggle off writes nothing and does not fail`() =
        runBlocking {
            autoBackup.setEnabled(false)

            assertEquals(ListenableWorker.Result.success(), run())

            assertEquals(emptyList<String>(), files.existing().map { it.name })
            assertNull(autoBackup.current.lastSuccessAt)
        }

    /**
     * A store that cannot be read is not a toggle that is off.
     *
     * `observe()` falls back to `enabled = false` so the backup screen always has a first frame
     * to draw, and a worker that read the flag through it would answer a transient DataStore
     * error at three in the morning by reporting success: no file, no retry, nothing recorded,
     * and a row still showing last week's date. The strict read throws, which puts the run in
     * the same retry path as a failed write.
     */
    @Test
    fun `a run that cannot read the toggle retries rather than skipping the week`() =
        runBlocking {
            autoBackup.readsFail = true

            assertEquals(ListenableWorker.Result.retry(), run())

            assertEquals(emptyList<String>(), files.existing().map { it.name })
            assertNull(autoBackup.current.lastSuccessAt)
        }

    /** And when the retries do not fix it, the row says so rather than staying quiet. */
    @Test
    fun `a toggle that stays unreadable is reported after the retries`() =
        runBlocking {
            autoBackup.readsFail = true

            assertEquals(ListenableWorker.Result.failure(), run(runAttemptCount = LAST_ATTEMPT))

            assertEquals(FIRST_SUNDAY, autoBackup.current.lastFailureAt)
            assertTrue(autoBackup.current.hasFailed)
        }

    /**
     * The first stumble is not news.
     *
     * A storage volume that is busy or briefly unavailable is usually not still that on the
     * next attempt, so an early failure asks for a retry and records nothing — the row only
     * earns a user's attention if it says "this has not worked", not "this did not work once".
     */
    @Test
    fun `a run that cannot write asks to be retried`() =
        runBlocking {
            blockTheBackupDirectory()

            assertEquals(ListenableWorker.Result.retry(), run())

            assertNull(autoBackup.current.lastFailureAt)
            assertFalse(autoBackup.current.hasFailed)
        }

    /** After the retries, the screen has to be able to say so. */
    @Test
    fun `the last attempt records the failure and stops`() =
        runBlocking {
            blockTheBackupDirectory()

            assertEquals(ListenableWorker.Result.failure(), run(runAttemptCount = LAST_ATTEMPT))

            assertEquals(FIRST_SUNDAY, autoBackup.current.lastFailureAt)
            assertTrue(autoBackup.current.hasFailed)
        }

    /** A failed run records nothing as having succeeded, whatever else it leaves behind. */
    @Test
    fun `a failed run leaves nothing a restore could pick up`() =
        runBlocking {
            db.seedEveryTable()
            blockTheBackupDirectory()

            run(runAttemptCount = LAST_ATTEMPT)

            assertEquals(emptyList<String>(), files.existing().map { it.name })
            assertNull(autoBackup.current.lastSuccessAt)
        }

    /**
     * Neither outcome interrupts the user, which is a decision rather than an omission.
     *
     * A successful backup is not news — it is the thing they asked for, happening — and a
     * failure at three in the morning is not something they can act on when it wakes them.
     * Both are recorded for the backup screen's row instead, which is where somebody looks
     * when they want to know. Asserted rather than assumed, because posting a notification is
     * one line away and nothing else would catch it.
     */
    @Test
    fun `neither a success nor a failure posts a notification`() =
        runBlocking {
            run()
            blockTheBackupDirectory()
            run(runAttemptCount = LAST_ATTEMPT)

            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            assertEquals(emptyList<String>(), notifications.orEmpty().map { it.packageName })
        }

    // --- helpers ----------------------------------------------------------

    private suspend fun run(runAttemptCount: Int = 0): ListenableWorker.Result = worker(runAttemptCount).doWork()

    private fun worker(runAttemptCount: Int): BackupWorker =
        TestListenableWorkerBuilder<BackupWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        BackupWorker(
                            appContext = appContext,
                            parameters = workerParameters,
                            exportBackup = export,
                            autoBackupRepository = autoBackup,
                            files = files,
                            clock = Clock { now },
                            // Unconfined: the work really is blocking, and a test that handed
                            // it the IO pool would be waiting on another thread for no reason.
                            ioDispatcher = Dispatchers.Unconfined,
                        )
                },
            )
            .build()

    /** A file where the directory should be, which is every storage failure at once. */
    private fun blockTheBackupDirectory() {
        files.directory.deleteRecursively()
        files.directory.parentFile?.mkdirs()
        files.directory.writeText("not a directory")
    }

    private fun backupFile(): File = files.existing().single()

    private fun source(file: File) = ImportSource { file.inputStream() }

    /** Empties the database through the same transaction an import uses, with an empty document. */
    private suspend fun wipeEverything() {
        backup.replaceAll(
            ExportDocument(
                exportedAt = now,
                appVersion = "",
                preferences = preferences.observe().first().toPayload(),
            ),
        ) { _, _ -> }
    }

    private companion object {

        /** 2026-04-26 00:00 UTC — tests run pinned to UTC, so the file name is the clock's own reading. */
        const val FIRST_SUNDAY = 1_777_161_600_000L

        const val WEEK_MILLIS = 7 * 24 * 60 * 60 * 1000L

        /** One more than are kept, which is what makes retention visible. */
        const val RUNS = BackupWorker.KEPT_BACKUPS + 1

        /**
         * `runAttemptCount` on the final attempt, counting from zero.
         *
         * Derived rather than written out, so raising the worker's limit moves what "the last
         * attempt" means here instead of leaving this testing a middle one.
         */
        const val LAST_ATTEMPT = BackupWorker.MAX_RUN_ATTEMPTS - 1
    }
}
