package com.titan.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.titan.core.domain.model.ActiveExercise
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Config
import java.io.File
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
        inProgressSessionRepository = InProgressSessionRepositoryImpl(context, Dispatchers.IO)
        templateDraftRepository = TemplateDraftRepositoryImpl(context, Dispatchers.IO)
        sessionStateRepository = SessionStateRepositoryImpl()
        reminderScheduler = RecordingReminderScheduler()
        repository = LocalDataRepositoryImpl(
            database = db,
            userPreferencesRepository = userPreferencesRepository,
            inProgressSessionRepository = inProgressSessionRepository,
            templateDraftRepository = templateDraftRepository,
            sessionStateRepository = sessionStateRepository,
            reminderScheduler = reminderScheduler,
            context = context,
            ioDispatcher = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
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

    // --- helpers ----------------------------------------------------------

    private companion object {

        /** 2024-03-04, so the "training since" line has a month behind it. */
        const val FIRST_SESSION_AT = 1_709_510_400_000L

        const val LATER_SESSION_AT = 1_788_600_000_000L

        /** What the seed puts in `workout_template` before the user has done anything. */
        val DEFAULT_TEMPLATES = DefaultTemplates.all.size
    }

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
