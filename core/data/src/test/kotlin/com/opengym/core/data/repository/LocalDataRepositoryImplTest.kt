package com.opengym.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.data.db.OpenGymDatabase
import com.opengym.core.data.db.bodyWeightLog
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.exercise
import com.opengym.core.data.db.personalRecord
import com.opengym.core.data.db.session
import com.opengym.core.data.db.sessionExercise
import com.opengym.core.data.db.template
import com.opengym.core.data.db.workoutSet
import com.opengym.core.domain.model.ActiveExercise
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
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
    private lateinit var db: OpenGymDatabase

    private lateinit var userPreferencesRepository: UserPreferencesRepositoryImpl
    private lateinit var inProgressSessionRepository: InProgressSessionRepositoryImpl
    private lateinit var sessionStateRepository: SessionStateRepositoryImpl
    private lateinit var repository: LocalDataRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OpenGymDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .addCallback(OpenGymDatabase.seedCallback(context.assets))
            .build()

        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(temporaryFolder.root, "user-preferences.preferences_pb")
        }

        userPreferencesRepository = UserPreferencesRepositoryImpl(dataStore)
        inProgressSessionRepository = InProgressSessionRepositoryImpl(context, Dispatchers.IO)
        sessionStateRepository = SessionStateRepositoryImpl()
        repository = LocalDataRepositoryImpl(
            database = db,
            userPreferencesRepository = userPreferencesRepository,
            inProgressSessionRepository = inProgressSessionRepository,
            sessionStateRepository = sessionStateRepository,
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
    fun `a fresh install has a footprint of zero despite the seeded catalogue`() = runBlocking {
        // The catalogue is thousands of rows and none of them are the user's, so a fresh
        // install must read as "nothing to lose" rather than as a full device.
        assertEquals(DataFootprint(), repository.observeFootprint().first())
        assertTrue(repository.observeFootprint().first().isEmpty)
    }

    @Test
    fun `the footprint counts the user's own rows, per kind`() = runBlocking {
        seedUserData()

        assertEquals(
            DataFootprint(
                sessions = 1,
                sets = 2,
                templates = 1,
                personalRecords = 1,
                customExercises = 1,
                bodyWeightLogs = 1,
            ),
            repository.observeFootprint().first(),
        )
    }

    @Test
    fun `a seeded exercise is not counted as the user's, a custom one is`() = runBlocking {
        db.exerciseDao().insert(exercise(name = "Not the user's", equipmentType = "CABLE"))
        db.exerciseDao().insert(ExerciseEntity(name = "Sandbag carry", isCustom = true))

        assertEquals(1, repository.observeFootprint().first().customExercises)
    }

    // --- the wipe ---------------------------------------------------------

    @Test
    fun `deleteAll leaves nothing of the user's behind`() = runBlocking {
        seedUserData()

        repository.deleteAll()

        assertEquals(DataFootprint(), repository.observeFootprint().first())
    }

    @Test
    fun `deleteAll restores the seeded catalogue it had to clear`() = runBlocking {
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
    fun `deleteAll puts every setting back to its default`() = runBlocking {
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
    fun `deleteAll discards the in-progress draft and returns the session state to Idle`() =
        runBlocking {
            inProgressSessionRepository.save(draft())
            sessionStateRepository.update(SessionState.Active(sessionId = 7, startedAt = 1_000))

            repository.deleteAll()

            assertNull(inProgressSessionRepository.load())
            assertEquals(SessionState.Idle, sessionStateRepository.observe().first())
        }

    @Test
    fun `a wipe is idempotent`() = runBlocking {
        seedUserData()

        repository.deleteAll()
        repository.deleteAll()

        assertEquals(DataFootprint(), repository.observeFootprint().first())
        assertEquals(UserPreferences(), userPreferencesRepository.observe().first())
    }

    // --- helpers ----------------------------------------------------------

    /** One row of every kind the footprint counts. */
    private suspend fun seedUserData() {
        val exerciseId = db.exerciseDao().insert(ExerciseEntity(name = "Sandbag carry", isCustom = true))
        db.workoutTemplateDao().insert(template())
        val sessionId = db.workoutSessionDao().insert(session(isInProgress = false))
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
        db.bodyWeightLogDao().insert(bodyWeightLog())
    }

    private fun draft() = ActiveSession(
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
