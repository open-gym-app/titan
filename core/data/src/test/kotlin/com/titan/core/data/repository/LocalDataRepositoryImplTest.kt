package com.titan.core.data.repository

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.backup.AutoBackupFiles
import com.titan.core.data.db.DefaultTemplates
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.db.bodyMeasurement
import com.titan.core.data.db.entity.ExerciseEntity
import com.titan.core.data.db.exercise
import com.titan.core.data.db.personalRecord
import com.titan.core.data.db.session
import com.titan.core.data.db.sessionExercise
import com.titan.core.data.db.template
import com.titan.core.data.db.workoutSet
import com.titan.core.data.di.DATABASE_NAME
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.notification.AndroidBackgroundRestTimer
import com.titan.core.data.notification.AndroidIdleSessionMonitor
import com.titan.core.data.notification.AndroidNotifier
import com.titan.core.data.notification.AppForegroundState
import com.titan.core.data.notification.RestTimerBroadcasts
import com.titan.core.domain.backup.AutoBackupSchedule
import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.backup.ExportFormat
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.model.ActiveExercise
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.AppNotification
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.time.Clock
import com.titan.core.domain.timer.BackgroundRest
import com.titan.core.domain.timer.RestSkipRequests
import com.titan.core.domain.timer.RestTimerSnapshot
import com.titan.core.domain.usecase.ExportUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The wipe behind settings' "delete all data", against all four real stores.
 *
 * Every collaborator here is the production implementation over a real file or a real
 * SQLite: a wipe is exactly the operation whose value is in what it *actually* removes, and
 * a test built on doubles would assert that five mocks were called and prove nothing about
 * whether a session survives. The database is built with the same seed callback the app
 * uses, so the catalogue-is-restored assertion runs the real asset through the real replay
 * rather than a stand-in for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocalDataRepositoryImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var db: TitanDatabase

    private lateinit var userPreferencesRepository: UserPreferencesRepositoryImpl
    private lateinit var inProgressSessionRepository: InProgressSessionRepositoryImpl
    private lateinit var templateDraftRepository: TemplateDraftRepositoryImpl
    private lateinit var sessionStateRepository: SessionStateRepositoryImpl

    /** The reminder alarms, which a wipe has to take down with the settings that armed them. */
    private lateinit var reminderScheduler: RecordingReminderScheduler
    private lateinit var repository: LocalDataRepositoryImpl

    /*
     * The workout's state outside the app: the real rest timer and idle monitor over Robolectric's
     * own `AlarmManager` and `NotificationManager`, so "nothing is left" is a question asked of the
     * system rather than of a double that was or was not called.
     */
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val notifications: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var restTimer: AndroidBackgroundRestTimer
    private lateinit var idleSessionMonitor: AndroidIdleSessionMonitor
    private val backupFiles = AutoBackupFiles(context)

    /** The weekly backup's own store, over its own file, as `AutoBackupModule` builds it. */
    private lateinit var autoBackupStore: DataStore<Preferences>
    private lateinit var autoBackupRepository: AutoBackupRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, TitanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(TitanDatabase.foreignKeysCallback)
            .addCallback(TitanDatabase.singleRowProgressCallback)
            .addCallback(TitanDatabase.seedCallback(context))
            .build()

        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(temporaryFolder.root, "user-preferences.preferences_pb")
        }

        userPreferencesRepository = UserPreferencesRepositoryImpl(dataStore)
        autoBackupStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(temporaryFolder.root, "auto-backup.preferences_pb")
        }
        autoBackupRepository = AutoBackupRepositoryImpl(autoBackupStore)
        inProgressSessionRepository = InProgressSessionRepositoryImpl(context, Dispatchers.IO)
        templateDraftRepository = TemplateDraftRepositoryImpl(context, Dispatchers.IO)
        sessionStateRepository = SessionStateRepositoryImpl()
        reminderScheduler = RecordingReminderScheduler()
        sessionRepository = SessionRepositoryImpl(db, SessionMapper(), SessionExerciseMapper(), WorkoutSetMapper())
        val notifier = AndroidNotifier(context, userPreferencesRepository, NotificationsAllowed)
        restTimer = AndroidBackgroundRestTimer(context, notifier, InBackground)
        idleSessionMonitor = AndroidIdleSessionMonitor(context, sessionRepository, notifier, Clock { NOW })
        repository = LocalDataRepositoryImpl(
            database = db,
            userPreferencesRepository = userPreferencesRepository,
            inProgressSessionRepository = inProgressSessionRepository,
            templateDraftRepository = templateDraftRepository,
            sessionStateRepository = sessionStateRepository,
            backgroundRestTimer = restTimer,
            idleSessionMonitor = idleSessionMonitor,
            reminderScheduler = reminderScheduler,
            backupFiles = backupFiles,
            autoBackupRepository = autoBackupRepository,
            context = context,
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
        backupFiles.directory.deleteRecursively()
    }

    // --- footprint --------------------------------------------------------

    @Test
    fun `a fresh install counts the default templates and nothing of the catalogue`() =
        runBlocking {
            // The catalogue is thousands of rows and none of them are the user's. The default
            // templates are counted: from the moment they are seeded they are ordinary
            // templates, the user's to edit and delete, and the wipe takes them like any other.
            assertEquals(DataFootprint(templates = DEFAULT_TEMPLATES), repository.observeFootprint().first())
        }

    @Test
    fun `the footprint counts the user's own rows, per kind`() =
        runBlocking {
            seedUserData()

            assertEquals(
                DataFootprint(
                    sessions = 1,
                    sets = 2,
                    templates = DEFAULT_TEMPLATES + 1,
                    personalRecords = 1,
                    customExercises = 1,
                    bodyMeasurements = 1,
                    firstSessionAt = FIRST_SESSION_AT,
                ),
                repository.observeFootprint().first(),
            )
        }

    /**
     * The oldest session's *start*, which is what "training since March 2024" is drawn from —
     * see #38's footprint card.
     */
    @Test
    fun `the footprint dates the device to its oldest session`() =
        runBlocking {
            db.workoutSessionDao().insert(session(createdAt = FIRST_SESSION_AT, lastActiveAt = FIRST_SESSION_AT))
            db.workoutSessionDao().insert(session(createdAt = LATER_SESSION_AT, lastActiveAt = LATER_SESSION_AT))

            assertEquals(FIRST_SESSION_AT, repository.observeFootprint().first().firstSessionAt)
        }

    /**
     * Null rather than zero on a device that has never logged one: `MIN` over no rows is NULL,
     * and a count-shaped query would have dated a fresh install to 1970.
     */
    @Test
    fun `a device with no sessions has no date rather than the epoch`() =
        runBlocking {
            assertNull(repository.observeFootprint().first().firstSessionAt)
        }

    /**
     * The database name the size is measured under is the one `DatabaseModule` opens.
     *
     * The size itself cannot be asserted here — every test builds an *in-memory* database, so
     * there is no file to stat and the figure is legitimately zero. What can be pinned is the
     * thing that would silently make it zero on a real device too: a rename of the file that
     * `LocalDataRepositoryImpl` does not follow. It cannot, now — [DATABASE_NAME] is the
     * production constant `DatabaseModule` opens Room with and the one the size is stat'd
     * under — and this is the literal that says which file that is.
     */
    @Test
    fun `the size is measured against the database the app actually opens`() {
        assertEquals("titan.db", DATABASE_NAME)
        assertEquals(0L, runBlocking { repository.observeFootprint().first().databaseBytes })
    }

    @Test
    fun `a seeded exercise is not counted as the user's, a custom one is`() =
        runBlocking {
            db.exerciseDao().insert(exercise(name = "Not the user's", equipmentType = "CABLE"))
            db.exerciseDao().insert(ExerciseEntity(name = "Sandbag carry", isCustom = true))

            assertEquals(1, repository.observeFootprint().first().customExercises)
        }

    // --- the wipe ---------------------------------------------------------

    @Test
    fun `deleteAll leaves nothing of the user's behind`() =
        runBlocking {
            seedUserData()

            repository.deleteAll()

            // Only the defaults, put back by the re-seed, as on a fresh install.
            assertEquals(DataFootprint(templates = DEFAULT_TEMPLATES), repository.observeFootprint().first())
        }

    @Test
    fun `deleteAll brings back the default templates as a fresh install has them`() =
        runBlocking {
            val dao = db.workoutTemplateDao()
            val defaults = dao.getAll().first().map { it.name }
            // A default deleted, another renamed, and one of the user's own.
            val all = dao.getAll().first()
            dao.delete(all.first { it.name == "Legs" }.id)
            dao.update(all.first { it.name == "Push" }.copy(name = "My push"))
            dao.insert(template(name = "Mine"))

            repository.deleteAll()

            assertEquals(defaults, dao.getAll().first().map { it.name })
        }

    @Test
    fun `deleteAll restores the seeded catalogue it had to clear`() =
        runBlocking {
            val seededBefore = db.exerciseDao().getAll().first().size
            val muscleGroupsBefore = db.muscleGroupDao().getAll().first().size
            db.exerciseDao().insert(ExerciseEntity(name = "Sandbag carry", isCustom = true))

            repository.deleteAll()

            // The catalogue is app content: an exercise library that empties itself when the
            // user clears their history is a broken app, not a clean one.
            assertEquals(seededBefore, db.exerciseDao().getAll().first().size)
            assertEquals(muscleGroupsBefore, db.muscleGroupDao().getAll().first().size)
            // The user's own exercise is not restored. It was theirs.
            assertTrue(db.exerciseDao().getAll().first().none { it.name == "Sandbag carry" })
        }

    @Test
    fun `deleteAll puts every setting back to its default`() =
        runBlocking {
            userPreferencesRepository.setTheme(ThemePreference.DARK)
            userPreferencesRepository.setUnitSystem(UnitSystem.IMPERIAL)
            userPreferencesRepository.setDefaultRestDuration(240.seconds)
            userPreferencesRepository.setOnboardingCompleted(true)
            assertNotEquals(UserPreferences(), userPreferencesRepository.observe().first())

            repository.deleteAll()

            // Including `onboardingCompleted`, which is what makes the next launch ask the
            // first-run questions again — the right behaviour for a device just emptied.
            assertEquals(UserPreferences(), userPreferencesRepository.observe().first())
        }

    @Test
    fun `deleteAll takes the reminder's alarms down with the settings that armed them`() =
        runBlocking {
            userPreferencesRepository.setNotificationEnabled(NotificationKind.REMINDERS, enabled = true)

            repository.deleteAll()

            // The switch goes back to off with everything else, so an alarm left standing would
            // buzz at 18:00 on a Monday for a reminder the settings screen reports as off. The
            // scheduler re-reads the store it has just been emptied and cancels what it finds.
            assertEquals(1, reminderScheduler.syncs)
        }

    @Test
    fun `deleteAll discards the in-progress draft and returns the session state to Idle`() =
        runBlocking {
            inProgressSessionRepository.save(draft())
            sessionStateRepository.update(SessionState.Active(sessionId = 7, startedAt = 1_000))

            repository.deleteAll()

            assertNull(inProgressSessionRepository.load())
            assertEquals(SessionState.Idle, sessionStateRepository.observe().first())
        }

    /**
     * The weekly backup and a delete-all used to be free to interleave: the backup paging the
     * history while the wipe cleared it under it (#257). They share `BackupLock` now — the
     * export below is the one `BackupWorker` runs, with the lock it takes by default — so the
     * wipe waits until the file is whole, and the file holds the history from before it.
     */
    @Test
    fun `deleteAll waits for an export that is writing, which keeps the history from before it`() =
        runBlocking {
            seedUserData()
            sessionStateRepository.update(SessionState.Active(sessionId = 7, startedAt = 1_000))
            val export = ExportUseCase(
                backupRepository = BackupRepositoryImpl(db, Dispatchers.IO),
                userPreferencesRepository = userPreferencesRepository,
            )
            val exportStarted = CompletableDeferred<Unit>()
            val exportMayFinish = CountDownLatch(1)
            val text = StringBuilder()

            val exporting = launch(Dispatchers.IO) {
                val sink = ExportSink { chunk ->
                    exportStarted.complete(Unit)
                    // Blocking, on the thread holding the read transaction: the export is
                    // mid-document and stays there until the test lets it go.
                    exportMayFinish.await()
                    text.append(chunk)
                }
                export(sink, "1.0-test").collect()
            }
            exportStarted.await()
            val deleting = launch(Dispatchers.IO) { repository.deleteAll() }
            val stateWhileExporting = try {
                delay(WAIT_FOR_A_WIPE_MS)
                deleting.isActive to sessionStateRepository.observe().first()
            } finally {
                // Released whatever the assertions below say, so a failure reports rather than
                // leaving the export blocked and the test hanging on it.
                exportMayFinish.countDown()
            }
            exporting.join()
            deleting.join()

            assertTrue("the wipe should have been waiting for the export", stateWhileExporting.first)
            assertEquals(
                "the wipe must not have started — not even on the session state",
                SessionState.Active(sessionId = 7, startedAt = 1_000),
                stateWhileExporting.second,
            )

            val exported = (ExportFormat.decode { text.toString().byteInputStream() } as ExportFormat.Decoded.Success)
            assertEquals("the file holds the history from before the wipe", 1, exported.document.workoutSession.size)
            assertEquals(DataFootprint(templates = DEFAULT_TEMPLATES), repository.observeFootprint().first())
        }

    /** The wait is the one part of a wipe that may be abandoned, and abandoning it deletes nothing. */
    @Test
    fun `a deleteAll cancelled while it waits for a backup deletes nothing`() =
        runBlocking {
            seedUserData()
            val before = repository.observeFootprint().first()
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val backup = launch {
                BackupLock.SHARED.exclusively {
                    holding.complete(Unit)
                    release.await()
                }
            }
            holding.await()

            try {
                val deleting = launch(Dispatchers.IO) { repository.deleteAll() }
                delay(WAIT_FOR_A_WIPE_MS)
                deleting.cancelAndJoin()
            } finally {
                release.complete(Unit)
            }
            backup.join()

            assertEquals(before, repository.observeFootprint().first())
        }

    @Test
    fun `a wipe is idempotent`() =
        runBlocking {
            seedUserData()

            repository.deleteAll()
            repository.deleteAll()

            // The defaults once, not twice: the second wipe clears the first one's re-seed.
            assertEquals(DataFootprint(templates = DEFAULT_TEMPLATES), repository.observeFootprint().first())
            assertEquals(UserPreferences(), userPreferencesRepository.observe().first())
        }

    // --- what the wipe leaves outside the stores ----------------------------

    /**
     * The rest's note and its exact alarm are the operating system's, and outlive the process.
     * A wipe that left them would announce "Rest over" for a workout the user has just erased.
     */
    @Test
    fun `deleteAll takes the rest's note and alarm down`() =
        runBlocking {
            val sessionId = startWorkout()
            restTimer.set(runningRest(sessionId))
            assertTrue("precondition: the rest is in the shade", notifications.activeNotifications.isNotEmpty())
            assertTrue("precondition: the rest has an alarm", shadowOf(alarms).scheduledAlarms.isNotEmpty())

            repository.deleteAll()

            assertEquals(emptyList<String>(), showing())
            assertEquals(emptyList<Any>(), shadowOf(alarms).scheduledAlarms)
        }

    /** A paused rest has no alarm, and its note stays up until something takes it down. */
    @Test
    fun `deleteAll takes a paused rest's note down`() =
        runBlocking {
            val sessionId = startWorkout()
            restTimer.set(runningRest(sessionId).copy(rest = RestTimerSnapshot.Paused(45.seconds, 90.seconds)))
            assertTrue("precondition: the paused rest is in the shade", notifications.activeNotifications.isNotEmpty())

            repository.deleteAll()

            assertEquals(emptyList<String>(), showing())
        }

    @Test
    fun `deleteAll takes the idle nudge and its alarm down`() =
        runBlocking {
            userPreferencesRepository.setNotificationEnabled(NotificationKind.SESSION, enabled = true)
            startWorkout()
            idleSessionMonitor.arm()
            // The nudge as `IdleSessionBroadcasts` posts it once the alarm has gone off.
            AndroidNotifier(context, userPreferencesRepository, NotificationsAllowed).post(
                AppNotification(kind = NotificationKind.SESSION, title = "Still training?", body = "Nothing since"),
            )
            assertTrue("precondition: the nudge is in the shade", notifications.activeNotifications.isNotEmpty())
            assertTrue("precondition: the nudge has an alarm", shadowOf(alarms).scheduledAlarms.isNotEmpty())

            repository.deleteAll()

            assertEquals(emptyList<String>(), showing())
            assertEquals(emptyList<Any>(), shadowOf(alarms).scheduledAlarms)
        }

    /**
     * The alarm's own intent, delivered after the wipe — as it would be by an alarm that fired
     * in the gap, or by one the wipe did not reach. It names a session that no longer exists, so
     * nothing may be said about it.
     */
    @Test
    fun `an expiry that arrives after deleteAll posts nothing`() =
        runBlocking {
            val sessionId = startWorkout()
            restTimer.set(runningRest(sessionId))
            val expiry = pendingExpiry()

            repository.deleteAll()
            restTimerBroadcasts().handle(expiry)

            assertEquals(emptyList<String>(), showing())
        }

    /**
     * Every export and every automatic backup is the whole history in plain JSON. A wipe that
     * left them would leave the very thing the user asked to erase, in a directory a file
     * manager can open. The scratch files go too: a killed run leaves one the size of the
     * history.
     */
    @Test
    fun `deleteAll deletes every backup file, finished or not`() =
        runBlocking {
            val directory = backupFiles.directory.apply { mkdirs() }
            val written = listOf(
                "titan-auto-2026-11-01.json",
                "titan-auto-2026-11-08.json.tmp",
                "titan-backup-2026-11-09-1432.json",
                "titan-backup-2026-11-09-1433.json.tmp",
            ).map { File(directory, it).apply { writeText("{}") } }
            assertTrue(written.all(File::exists))

            repository.deleteAll()

            assertEquals(emptyList<String>(), directory.listFiles().orEmpty().map { it.name })
        }

    /**
     * A backup that will not delete is reported, but not before the steps that do not depend on
     * it: the settings, the record of past runs and the reminder alarms are reset anyway, so the
     * phone ends as close to what the dialog promised as it can. The file is made undeletable by
     * putting it in a directory nobody may write to.
     */
    @Test
    fun `a backup that will not delete is reported after the settings and alarms are reset`() =
        runBlocking {
            val locked = File(backupFiles.directory, "locked").apply { mkdirs() }
            File(locked, "titan-auto-2026-11-01.json").writeText("{}")
            check(locked.setWritable(false)) { "could not make $locked read-only" }
            userPreferencesRepository.setUnitSystem(UnitSystem.IMPERIAL)
            userPreferencesRepository.setNotificationEnabled(NotificationKind.REMINDERS, enabled = true)
            autoBackupRepository.recordSuccess(at = NOW)
            try {
                val failure = runCatching { repository.deleteAll() }.exceptionOrNull()

                assertTrue("expected the stuck file to be reported, got $failure", failure is IOException)
                assertEquals(UserPreferences(), userPreferencesRepository.observe().first())
                assertEquals(AutoBackupSchedule(enabled = false), autoBackupRepository.read())
                assertEquals(1, reminderScheduler.syncs)
            } finally {
                locked.setWritable(true)
            }
        }

    /**
     * A backup written while no external volume was mounted went to the internal fallback, and
     * [AutoBackupFiles.directory] does not name that one while the external volume is back.
     */
    @Test
    fun `deleteAll deletes a backup written to internal storage while the external volume was away`() =
        runBlocking {
            val internal = File(context.filesDir, "backups").apply { mkdirs() }
            val written = File(internal, "titan-auto-2026-11-01.json").apply { writeText("{}") }
            assertTrue(written.exists())

            repository.deleteAll()

            assertEquals(emptyList<String>(), internal.listFiles().orEmpty().map { it.name })
        }

    /**
     * The dates under the toggle describe files the wipe has just deleted, so they go. The toggle
     * is the user's choice about the future and is left alone, and so is the schedule behind it:
     * cancelling the work under a switch still drawn on would be a promise with nothing behind it.
     */
    @Test
    fun `deleteAll forgets past automatic backups and keeps the toggle`() =
        runBlocking {
            autoBackupRepository.setEnabled(true)
            autoBackupRepository.recordSuccess(at = NOW)
            autoBackupRepository.recordFailure(at = NOW + 1)

            repository.deleteAll()

            assertEquals(AutoBackupSchedule(enabled = true), autoBackupRepository.read())
        }

    /**
     * `AUTOINCREMENT` never hands out a number below the largest it has seen, and `sqlite_sequence`
     * remembers that number through `DELETE FROM`. A row id near `Long.MAX_VALUE` — from a restore
     * made before #256 refused them — leaves nothing to assign, and every later insert fails with
     * `SQLITE_FULL`. A device emptied by the user is the one where nothing can still need the old
     * numbers.
     */
    @Test
    fun `a new session can be started after deleting data that held an id near the limit`() =
        runBlocking {
            db.workoutSessionDao().insert(session(isInProgress = false).copy(id = Long.MAX_VALUE))

            repository.deleteAll()

            val next = db.workoutSessionDao().insert(session(createdAt = NOW, lastActiveAt = NOW))
            assertEquals(1L, next)
        }

    // --- helpers ----------------------------------------------------------

    private companion object {

        /** 2024-03-04, so the "training since" line has a month behind it. */
        const val FIRST_SESSION_AT = 1_709_510_400_000L

        const val LATER_SESSION_AT = 1_788_600_000_000L

        /** What the seed puts in `workout_template` before the user has done anything. */
        val DEFAULT_TEMPLATES = DefaultTemplates.all.size

        /**
         * How long a test gives a wipe that should be waiting to show it is not: long enough
         * for one that was not held back to have reset the session state many times over.
         */
        const val WAIT_FOR_A_WIPE_MS = 300L

        /** The test's wall clock: the workout starts now, so its idle deadline is in the future. */
        const val NOW = 1_762_000_000_000L
    }

    /** The titles in the shade, which say *which* note was left when one was. */
    private fun showing(): List<String> =
        notifications.activeNotifications.map {
            it.notification.extras.getCharSequence(
                Notification.EXTRA_TITLE,
            ).toString()
        }

    /** An in-progress session row, started [NOW], as a workout under way has. */
    private suspend fun startWorkout(): Long =
        db.workoutSessionDao().insert(session(createdAt = NOW, lastActiveAt = NOW))

    private fun runningRest(sessionId: Long) =
        BackgroundRest(
            sessionId = sessionId,
            exerciseName = "Bench press",
            setNumber = 3,
            rest = RestTimerSnapshot.Running(deadline = NOW + 90_000, total = 90.seconds),
            sessionCreatedAt = NOW,
        )

    /**
     * The broadcast the rest's pending alarm would deliver, read off the scheduler.
     *
     * Robolectric 4.14 deprecates the field in favour of an accessor it does not ship yet.
     */
    @Suppress("DEPRECATION")
    private fun pendingExpiry(): Intent {
        val alarm = checkNotNull(shadowOf(alarms).peekNextScheduledAlarm()) { "no rest alarm is pending" }
        return shadowOf(checkNotNull(alarm.operation)).savedIntent
    }

    private fun restTimerBroadcasts() =
        RestTimerBroadcasts(restTimer, NoSkipRequests, inProgressSessionRepository, sessionRepository)

    /** One row of every kind the footprint counts. */
    private suspend fun seedUserData() {
        val exerciseId = db.exerciseDao().insert(ExerciseEntity(name = "Sandbag carry", isCustom = true))
        db.workoutTemplateDao().insert(template())
        val sessionId = db.workoutSessionDao().insert(
            session(isInProgress = false, createdAt = FIRST_SESSION_AT, lastActiveAt = FIRST_SESSION_AT),
        )
        val sessionExerciseId = db.sessionExerciseDao().insert(
            sessionExercise(sessionId = sessionId, exerciseId = exerciseId, position = 0),
        )
        val setId = db.workoutSetDao().insert(
            workoutSet(sessionExerciseId = sessionExerciseId, setNumber = 1, reps = 5, weight = 100_000_000),
        )
        db.workoutSetDao().insert(
            workoutSet(sessionExerciseId = sessionExerciseId, setNumber = 2, reps = 5, weight = 100_000_000),
        )
        db.personalRecordDao().insert(personalRecord(exerciseId = exerciseId, workoutSetId = setId))
        db.bodyMeasurementDao().insert(bodyMeasurement())
    }

    private fun draft() =
        ActiveSession(
            sessionId = 7,
            startedAt = 1_700_000_000_000,
            pausedDuration = 3.minutes,
            exercises = listOf(
                ActiveExercise(
                    exerciseId = 11,
                    sessionExerciseId = 21,
                    sets = listOf(
                        ActiveSet(
                            id = 31,
                            reps = 5,
                            weight = 100_000_000,
                            isCompleted = true,
                            completedAt = 1_700_000_060_000,
                        ),
                    ),
                ),
            ),
        )
}

/** A [ReminderScheduler] that counts syncs instead of touching an `AlarmManager`. */
private class RecordingReminderScheduler : ReminderScheduler {
    var syncs = 0

    override suspend fun sync() {
        syncs++
    }

    override fun isExact(): Boolean = true
}

/** Notifications the system would deliver, so whether one is showing is the code's doing. */
private object NotificationsAllowed : NotificationPermission {
    override fun areNotificationsAllowed(): Boolean = true

    override val isRuntimePermissionRequired: Boolean = true
}

/** The app away from the user, which is when the rest's expiry is a notification. */
private object InBackground : AppForegroundState {
    override suspend fun isInForeground(): Boolean = false
}

/** Nobody listening for a skip; these tests never press one. */
private object NoSkipRequests : RestSkipRequests {
    override val skipped: Flow<Long> = emptyFlow()

    override suspend fun skip(sessionId: Long) = Unit
}
