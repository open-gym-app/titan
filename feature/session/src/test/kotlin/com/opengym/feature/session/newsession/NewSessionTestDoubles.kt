package com.opengym.feature.session.newsession

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.OneRepMaxFormula
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
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration

/**
 * In-memory stores behind the four repositories the new-session flow reads and writes.
 *
 * Hand-written rather than mocked, because what is under test is *what ends up stored*: half
 * the assertions are "backing out wrote nothing" and "starting laid the exercises out in
 * order", and a mock verifying calls would assert that the ViewModel made a request rather
 * than that the session came out right.
 *
 * They honour the parts of the contract the flow leans on — position numbering from list
 * order, templates ordered by name, one session at a time — so a test that passes here is
 * testing the flow and not the fake. Methods the flow never reaches are left unsupported
 * rather than given a plausible body nobody has checked.
 */
class FakeTemplateStore : TemplateRepository {

    private val templates = MutableStateFlow<Map<Long, WorkoutTemplate>>(emptyMap())
    private val exercises = MutableStateFlow<Map<Long, List<TemplateExercise>>>(emptyMap())
    private var nextTemplateId = 1L
    private var nextExerciseId = 1L

    override fun getAll(): Flow<List<WorkoutTemplate>> =
        templates.map { stored -> stored.values.sortedBy(WorkoutTemplate::name) }

    override fun getById(id: Long): Flow<WorkoutTemplate?> = templates.map { it[id] }

    override fun getExercises(templateId: Long): Flow<List<TemplateExercise>> =
        exercises.map { it[templateId].orEmpty() }

    override fun getAllExercises(): Flow<List<TemplateExercise>> = exercises.map { stored -> stored.values.flatten() }

    override suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long = seed(template.name, exercises, template.category)

    override suspend fun update(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ) {
        templates.update { it + (template.id to template) }
        store(template.id, exercises)
    }

    override suspend fun delete(id: Long) {
        templates.update { it - id }
        exercises.update { it - id }
    }

    /** Puts a template in the store and returns its id. */
    fun seed(
        name: String,
        drafts: List<TemplateExerciseDraft>,
        category: TemplateCategory? = null,
    ): Long {
        val id = nextTemplateId++
        templates.update { it + (id to WorkoutTemplate(id = id, name = name, category = category)) }
        store(id, drafts)
        return id
    }

    /** The ids of [templateId]'s exercise rows, in position order, for assertions. */
    fun exerciseRowIds(templateId: Long): List<Long> = exercises.value[templateId].orEmpty().map(TemplateExercise::id)

    private fun store(
        templateId: Long,
        drafts: List<TemplateExerciseDraft>,
    ) {
        exercises.update { stored ->
            stored + (
                templateId to drafts.mapIndexed { position, draft ->
                    TemplateExercise(
                        id = nextExerciseId++,
                        templateId = templateId,
                        exerciseId = draft.exerciseId,
                        position = position,
                        supersetGroup = draft.supersetGroup,
                        sets = draft.sets,
                        restDuration = draft.restDuration,
                    )
                }
                )
        }
    }
}

/** The workout sessions and their layouts. */
class FakeSessionStore : SessionRepository {

    private val sessions = MutableStateFlow<Map<Long, WorkoutSession>>(emptyMap())
    private val exercises = MutableStateFlow<Map<Long, List<SessionExercise>>>(emptyMap())
    private val sets = MutableStateFlow<Map<Long, List<WorkoutSet>>>(emptyMap())
    private var nextSessionId = 1L
    private var nextExerciseId = 1L

    /** When set, laying a session out throws it — the store that cannot be written. */
    var layoutFailure: Throwable? = null

    /** When set, deleting a session throws it — the workout that cannot be thrown away. */
    var deleteFailure: Throwable? = null

    override fun getAll(): Flow<List<WorkoutSession>> = sessions.map { it.values.toList() }

    override fun getById(id: Long): Flow<WorkoutSession?> = sessions.map { it[id] }

    override fun getActive(): Flow<WorkoutSession?> =
        sessions.map { stored ->
            stored.values.filter { it.isInProgress }.maxByOrNull { it.lastActiveAt }
        }

    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> = exercises.map { it[sessionId].orEmpty() }

    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> =
        throw UnsupportedOperationException("the new-session flow logs nothing")

    // Read, not written: the flow logs nothing, but the offer to pick a running workout back
    // up shows how much is in it — see `NewSessionUiState.RunningWorkout`.
    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> = sets.map { it[sessionId].orEmpty() }

    override fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>> =
        throw UnsupportedOperationException("the new-session flow logs nothing")

    override fun getTotals(): Flow<Map<Long, SessionTotals>> =
        throw UnsupportedOperationException("the new-session flow logs nothing")

    override suspend fun countFinished(): Int = throw UnsupportedOperationException("the new-session flow logs nothing")

    override suspend fun insert(session: WorkoutSession): Long {
        val id = nextSessionId++
        sessions.update { it + (id to session.copy(id = id)) }
        return id
    }

    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> {
        layoutFailure?.let { throw it }

        val stored = exercises.mapIndexed { position, draft ->
            SessionExercise(
                id = nextExerciseId++,
                sessionId = sessionId,
                exerciseId = draft.exerciseId,
                position = position,
                supersetGroup = draft.supersetGroup,
                templateExerciseId = draft.templateExerciseId,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
        this.exercises.update { it + (sessionId to stored) }
        return stored.map(SessionExercise::id)
    }

    override suspend fun insertSet(set: WorkoutSet): Long =
        throw UnsupportedOperationException("the new-session flow logs nothing")

    /**
     * Kept, not refused: starting a session from a template lays its plan down as sets, so this
     * is on the path the flow's own START takes.
     */
    override suspend fun insertSets(sets: List<WorkoutSet>): List<Long> {
        val stored = sets.map { it.copy(id = nextSetId++) }
        plannedSets += stored
        return stored.map(WorkoutSet::id)
    }

    /** Every set written as part of laying a plan out, in the order it was written. */
    val plannedSets = mutableListOf<WorkoutSet>()

    private var nextSetId = 1L

    override suspend fun updateSet(set: WorkoutSet) =
        throw UnsupportedOperationException("the new-session flow logs nothing")

    override suspend fun updateSets(sets: List<WorkoutSet>) =
        throw UnsupportedOperationException("the new-session flow logs nothing")

    override suspend fun deleteSet(setId: Long) =
        throw UnsupportedOperationException("the new-session flow logs nothing")

    override suspend fun updateExercise(exercise: SessionExercise) =
        throw UnsupportedOperationException("the new-session flow edits no layout")

    override suspend fun updateExercises(exercises: List<SessionExercise>) =
        throw UnsupportedOperationException("the new-session flow edits no layout")

    override suspend fun deleteExercise(sessionExerciseId: Long) =
        throw UnsupportedOperationException("the new-session flow edits no layout")

    override suspend fun update(session: WorkoutSession) {
        sessions.update { it + (session.id to session) }
    }

    override suspend fun delete(id: Long) {
        deleteFailure?.let { throw it }
        sessions.update { it - id }
        exercises.update { it - id }
    }

    /** [count] completed sets against [sessionId], for the workout that is already running. */
    fun seedSets(
        sessionId: Long,
        count: Int,
    ) {
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

    /** Direct reads, bypassing the Flows, for assertions. */
    fun storedSessions(): List<WorkoutSession> = sessions.value.values.toList()

    fun storedExercises(sessionId: Long): List<SessionExercise> = exercises.value[sessionId].orEmpty()

    /** Every session exercise in the store, however many sessions there are. */
    fun allStoredExercises(): List<SessionExercise> = exercises.value.values.flatten()
}

/** The session state machine. */
class FakeSessionStateStore(initial: SessionState = SessionState.Idle) : SessionStateRepository {

    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<SessionState> = state.asStateFlow()

    override suspend fun update(state: SessionState) {
        this.state.value = state
    }

    val current: SessionState get() = state.value
}

/** The exercise catalogue, read to turn ids into rows the review panel can draw. */
class FakeExerciseCatalogue(
    private val exercises: List<Exercise> = emptyList(),
    private val links: Map<Long, List<ExerciseMuscleGroupLink>> = emptyMap(),
) : ExerciseRepository {

    override fun getAll(): Flow<List<Exercise>> = MutableStateFlow(exercises)

    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> = MutableStateFlow(links)

    override fun getById(id: Long): Flow<Exercise?> = MutableStateFlow(exercises.find { it.id == id })

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

    override suspend fun update(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ) = unsupported()

    override suspend fun delete(id: Long) = unsupported()

    /** The catalogue is read, never written or searched, from this flow. */
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the new-session flow only reads the catalogue")
}

/** The settings store, for the one field the template estimate is priced against. */
class FakeSettings(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = write { it.copy(unitSystem = unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) = write { it.copy(defaultRestDuration = duration) }

    override suspend fun setBarWeight(barWeight: Long) = write { it.copy(barWeight = barWeight) }

    override suspend fun setPlateInventory(inventory: PlateInventory?) = write { it.copy(plateInventory = inventory) }

    /** Present to satisfy the interface. No screen in this flow reaches a rack. */
    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) = write { it.copy(plateInventory = it.plates.withPairs(weight, pairs)) }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

    override suspend fun setOnboardingCompleted(completed: Boolean) = write { it.copy(onboardingCompleted = completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write { it.copy(notifications = it.notifications.with(kind, enabled)) }

    override suspend fun setNotificationPermissionRequested() =
        write { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }

    override suspend fun setReminderDay(
        day: DayOfWeek,
        enabled: Boolean,
    ) = write {
        val days = it.reminders.days
        it.copy(reminders = it.reminders.copy(days = if (enabled) days + day else days - day))
    }

    override suspend fun setReminderTime(time: LocalTime) =
        write { it.copy(reminders = it.reminders.copy(timeOfDay = time)) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}
