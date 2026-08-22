package com.opengym.feature.home

import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionTotals
import com.opengym.core.domain.model.Tag
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/*
 * The stores behind everything Home reads, in memory.
 *
 * Hand-written rather than mocked, for the reason the other screens' doubles give: what is
 * under test is the state that comes out — a banner assembled from three reads, and a week
 * counted off a history — and asserting on that is both shorter and stricter than stubbing
 * each read in turn. The discard path is the strictest case of all: it runs two real use cases
 * over these stores, so "the banner goes away" is the row actually being deleted rather than a
 * verified call.
 *
 * Methods Home never reaches are left unsupported rather than given a plausible body nobody
 * has checked.
 */

/** A clock whose reading only changes when a test says so. */
class FixedClock(var now: Long) : Clock {
    override fun nowMillis(): Long = now
}

/** The sessions, their sets, and which one is still open. */
class FakeSessionStore(sessions: List<WorkoutSession> = emptyList()) : SessionRepository {

    private val stored = MutableStateFlow(sessions.associateBy(WorkoutSession::id))
    private val sets = MutableStateFlow<Map<Long, List<WorkoutSet>>>(emptyMap())

    /** When set, deleting a session throws it — the workout that cannot be thrown away. */
    var deleteFailure: Throwable? = null

    override fun getAll(): Flow<List<WorkoutSession>> = stored.map { it.values.toList() }

    override fun getById(id: Long): Flow<WorkoutSession?> = stored.map { it[id] }

    override fun getActive(): Flow<WorkoutSession?> = stored.map { sessions ->
        sessions.values.filter(WorkoutSession::isInProgress).maxByOrNull(WorkoutSession::lastActiveAt)
    }

    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> =
        sets.map { it[sessionId].orEmpty() }

    override suspend fun update(session: WorkoutSession) {
        stored.update { it + (session.id to session) }
    }

    override suspend fun delete(id: Long) {
        deleteFailure?.let { throw it }
        stored.update { it - id }
        sets.update { it - id }
    }

    /** [count] logged sets against [sessionId] — what discarding it would cost. */
    fun seedSets(sessionId: Long, count: Int) {
        sets.update {
            it + (
                sessionId to (1..count).map { number ->
                    WorkoutSet(
                        id = number.toLong(),
                        sessionExerciseId = sessionId,
                        setNumber = number,
                        reps = 8,
                        weight = 60_000_000L,
                        isCompleted = true,
                        completedAt = 1L,
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                }
                )
        }
    }

    fun setSessions(sessions: List<WorkoutSession>) {
        stored.value = sessions.associateBy(WorkoutSession::id)
    }

    /** Direct reads, bypassing the flows, for assertions. */
    fun storedSessions(): List<WorkoutSession> = stored.value.values.toList()

    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> = unsupported()
    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> = unsupported()
    override fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>> = unsupported()
    override fun getTotals(): Flow<Map<Long, SessionTotals>> = unsupported()
    override suspend fun insert(session: WorkoutSession): Long = unsupported()
    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> = unsupported()

    override suspend fun insertSet(set: WorkoutSet): Long = unsupported()
    override suspend fun updateSet(set: WorkoutSet) = unsupported()
    override suspend fun updateSets(sets: List<WorkoutSet>) = unsupported()
    override suspend fun deleteSet(setId: Long) = unsupported()
    override suspend fun updateExercise(exercise: SessionExercise) = unsupported()
    override suspend fun updateExercises(exercises: List<SessionExercise>) = unsupported()
    override suspend fun deleteExercise(sessionExerciseId: Long) = unsupported()

    /** Home reads sessions and deletes one. Anything else is a change of behaviour. */
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("home reads sessions and discards one")
}

/** The in-progress draft: one slot, which is what the contract says it is. */
class FakeDraftStore(initial: ActiveSession? = null) : InProgressSessionRepository {

    private var draft: ActiveSession? = initial

    var clearCount = 0
        private set

    override suspend fun save(session: ActiveSession) {
        draft = session
    }

    override suspend fun load(): ActiveSession? = draft

    override suspend fun clear() {
        clearCount++
        draft = null
    }
}

/** The session state machine. In memory, exactly as the real one is. */
class FakeSessionStateStore(initial: SessionState = SessionState.Idle) : SessionStateRepository {

    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<SessionState> = state.asStateFlow()

    override suspend fun update(state: SessionState) {
        this.state.value = state
    }

    val current: SessionState get() = state.value
}

/** The catalogue, read for the one name the resume banner puts in its position line. */
class FakeExerciseCatalogue(private val exercises: List<Exercise> = emptyList()) : ExerciseRepository {

    override fun getById(id: Long): Flow<Exercise?> = MutableStateFlow(exercises.find { it.id == id })

    override fun getAll(): Flow<List<Exercise>> = unsupported()
    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> = unsupported()
    override fun getByType(type: ExerciseType): Flow<List<Exercise>> = unsupported()
    override fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>> = unsupported()
    override fun searchByPartialName(query: String): Flow<List<Exercise>> = unsupported()
    override fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>> = unsupported()
    override fun getTags(exerciseId: Long): Flow<List<Tag>> = unsupported()
    override fun getMuscleGroups(): Flow<List<MuscleGroup>> = unsupported()
    override suspend fun insert(exercise: Exercise): Long = unsupported()
    override suspend fun insert(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ): Long = unsupported()

    override suspend fun update(exercise: Exercise) = unsupported()
    override suspend fun update(exercise: Exercise, muscleGroups: List<ExerciseMuscleGroupLink>) =
        unsupported()

    override suspend fun delete(id: Long) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("home reads one exercise by id")
}

/** The saved templates. Only the two reads `GetTemplateSummariesUseCase` makes are real. */
class FakeTemplateStore(
    templates: List<WorkoutTemplate> = emptyList(),
    exercises: List<TemplateExercise> = emptyList(),
) : TemplateRepository {

    private val stored = MutableStateFlow(templates)
    private val body = MutableStateFlow(exercises)

    override fun getAll(): Flow<List<WorkoutTemplate>> =
        stored.map { it.sortedBy(WorkoutTemplate::name) }

    override fun getAllExercises(): Flow<List<TemplateExercise>> = body

    override fun getById(id: Long): Flow<WorkoutTemplate?> = unsupported()
    override fun getExercises(templateId: Long): Flow<List<TemplateExercise>> = unsupported()
    override suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long = unsupported()

    override suspend fun update(template: WorkoutTemplate, exercises: List<TemplateExerciseDraft>) =
        unsupported()

    override suspend fun delete(id: Long) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("home only lists templates")
}

/** The settings store, for the one field the template estimate is priced against. */
class FakeSettings(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = write { it.copy(unitSystem = unitSystem) }
    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }
    override suspend fun setDefaultRestDuration(duration: Duration) =
        write { it.copy(defaultRestDuration = duration) }

    override suspend fun setBarWeight(barWeight: Long) = write { it.copy(barWeight = barWeight) }
    override suspend fun setPlateInventory(inventory: PlateInventory?) =
        write { it.copy(plateInventory = inventory) }

    override suspend fun setPlatePairs(weight: Long, pairs: Int) =
        write { it.copy(plateInventory = it.plates.withPairs(weight, pairs)) }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) =
        write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        write { it.copy(onboardingCompleted = completed) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}

/** A saved template, with [exerciseCount] rows under it so its estimate is not zero. */
fun template(
    id: Long,
    name: String,
    exerciseCount: Int = 1,
    category: TemplateCategory? = null,
): Pair<WorkoutTemplate, List<TemplateExercise>> =
    WorkoutTemplate(id = id, name = name, category = category) to List(exerciseCount) { position ->
        TemplateExercise(
            id = id * 100 + position,
            templateId = id,
            exerciseId = position + 1L,
            position = position,
            defaultSets = 3,
        )
    }
