package com.opengym.feature.session.summary

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.Tag
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.TemplateRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory stores behind the four repositories the recap reads, and the one it writes.
 *
 * Hand-written rather than mocked, for the reason the new-session flow's doubles give: what is
 * under test is *what the screen ends up showing* for a given session on disk, and a mock
 * verifying calls would assert that the ViewModel asked a question rather than that it got the
 * answer right.
 *
 * They honour the parts of the contract the recap leans on — `getSetsForSession` returning
 * every set of a session across its exercises, muscle links keyed by exercise, one headline
 * record per exercise — so a test that passes here is testing the recap and not the fake.
 * Methods it never reaches are left unsupported rather than given a plausible body nobody has
 * checked.
 */
class FakeSummarySessionStore(
    session: WorkoutSession? = null,
    exercises: List<SessionExercise> = emptyList(),
    sets: List<WorkoutSet> = emptyList(),
) : SessionRepository {

    private val sessions = MutableStateFlow(listOfNotNull(session).associateBy(WorkoutSession::id))
    private val exercises = MutableStateFlow(exercises)
    private val sets = MutableStateFlow(sets)

    /** When set, writing a session throws it — the store that cannot be written. */
    var writeFailure: Throwable? = null

    override fun getById(id: Long): Flow<WorkoutSession?> = sessions.map { it[id] }

    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> =
        exercises.map { stored -> stored.filter { it.sessionId == sessionId } }

    /**
     * Every set of the session, across its exercises, in one read — the contract the real
     * repository is held to, and what lets the recap group by exercise without a second query.
     */
    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> {
        val owned = exercises.value.filter { it.sessionId == sessionId }.map(SessionExercise::id).toSet()
        return sets.map { stored -> stored.filter { it.sessionExerciseId in owned } }
    }

    override suspend fun update(session: WorkoutSession) {
        writeFailure?.let { throw it }
        sessions.update { it + (session.id to session) }
    }

    /** The stored row, bypassing the Flow, for assertions about what a write left behind. */
    fun stored(id: Long): WorkoutSession? = sessions.value[id]

    override fun getAll(): Flow<List<WorkoutSession>> = unsupported()

    override fun getActive(): Flow<WorkoutSession?> = unsupported()

    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> = unsupported()

    override suspend fun insert(session: WorkoutSession): Long = unsupported()

    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> = unsupported()

    override suspend fun delete(id: Long) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the recap reads one session and writes its note")
}

/** The exercise catalogue, read to turn session rows into names and muscles. */
class FakeSummaryCatalogue(
    private val exercises: List<Exercise> = emptyList(),
    private val links: Map<Long, List<ExerciseMuscleGroupLink>> = emptyMap(),
    /**
     * When set, neither read ever answers — the catalogue that has not come back yet, as
     * opposed to one that is genuinely empty. The recap must not be able to tell them apart by
     * drawing the second.
     */
    private val unread: Boolean = false,
) : ExerciseRepository {

    override fun getAll(): Flow<List<Exercise>> =
        if (unread) emptyFlow() else MutableStateFlow(exercises)

    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> =
        if (unread) emptyFlow() else MutableStateFlow(links)

    override fun getById(id: Long): Flow<Exercise?> = unsupported()

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
        throw UnsupportedOperationException("the recap reads the catalogue in bulk and never writes it")
}

/**
 * The records the PR engine (#30) will write.
 *
 * One per exercise, which is all the repository exposes today — see the note on the recap's
 * ViewModel for what that costs and when it stops costing it.
 */
class FakeRecordStore(private val records: Map<Long, PersonalRecord> = emptyMap()) :
    PersonalRecordRepository {

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = MutableStateFlow(records)
}

/**
 * The template store the "save as template" write lands in.
 *
 * [getAll] is real, because `CreateTemplateUseCase`'s duplicate check reads it — so a name
 * already in use is refused here exactly the way it is on a device, through the domain's own
 * validation rather than through a fake that agrees with it.
 */
class FakeTemplateWriteStore : TemplateRepository {

    private val templates = MutableStateFlow<Map<Long, WorkoutTemplate>>(emptyMap())
    private val exercises = MutableStateFlow<Map<Long, List<TemplateExerciseDraft>>>(emptyMap())
    private var nextId = 1L

    /** When set, the insert throws it — the store that cannot be written. */
    var writeFailure: Throwable? = null

    /** When set, the insert waits on it — a write still in flight, for the dialog's guards. */
    var writeGate: CompletableDeferred<Unit>? = null

    override fun getAll(): Flow<List<WorkoutTemplate>> =
        templates.map { stored -> stored.values.sortedBy(WorkoutTemplate::name) }

    override suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long {
        writeGate?.await()
        writeFailure?.let { throw it }

        val id = nextId++
        templates.update { it + (id to template.copy(id = id)) }
        this.exercises.update { it + (id to exercises) }
        return id
    }

    /** Puts a template in the store, so a duplicate name has something to collide with. */
    fun seed(name: String) {
        val id = nextId++
        templates.update { it + (id to WorkoutTemplate(id = id, name = name)) }
    }

    /** What was written, for assertions. */
    fun stored(): List<WorkoutTemplate> = templates.value.values.toList()

    fun storedExercises(templateId: Long): List<TemplateExerciseDraft> =
        exercises.value[templateId].orEmpty()

    override fun getById(id: Long): Flow<WorkoutTemplate?> = unsupported()

    override fun getExercises(templateId: Long): Flow<List<TemplateExercise>> = unsupported()

    override fun getAllExercises(): Flow<List<TemplateExercise>> = unsupported()

    override suspend fun update(template: WorkoutTemplate, exercises: List<TemplateExerciseDraft>) =
        unsupported()

    override suspend fun delete(id: Long) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the recap only creates templates")
}

// ─── The session under test ─────────────────────────────────────────────────────────────

const val SESSION_ID = 1L

val CHEST = MuscleGroup(id = 1, name = "CHEST")
val TRICEPS = MuscleGroup(id = 2, name = "TRICEPS")
val QUADS = MuscleGroup(id = 3, name = "QUADRICEPS")

val BENCH = Exercise(id = 10, name = "Barbell bench press")
val PUSHDOWN = Exercise(id = 11, name = "Triceps pushdown")

/** Bench presses the chest and assists the triceps; the pushdown is a triceps isolation. */
val CATALOGUE_LINKS = mapOf(
    BENCH.id to listOf(
        ExerciseMuscleGroupLink(CHEST, isPrimary = true),
        ExerciseMuscleGroupLink(TRICEPS, isPrimary = false),
    ),
    PUSHDOWN.id to listOf(ExerciseMuscleGroupLink(TRICEPS, isPrimary = true)),
)

/** 10:00 to 11:00 on an arbitrary morning. */
const val STARTED_AT = 1_777_284_000_000L
const val FINISHED_AT = 1_777_287_600_000L

fun finishedSession(
    notes: String? = null,
    name: String? = "Push Day · Upper",
    completedAt: Long? = FINISHED_AT,
) = WorkoutSession(
    id = SESSION_ID,
    name = name,
    completedAt = completedAt,
    isInProgress = false,
    lastActiveAt = FINISHED_AT,
    notes = notes,
    createdAt = STARTED_AT,
    updatedAt = FINISHED_AT,
)

fun sessionExercise(id: Long, exerciseId: Long, position: Int, supersetGroup: Int? = null) =
    SessionExercise(
        id = id,
        sessionId = SESSION_ID,
        exerciseId = exerciseId,
        position = position,
        supersetGroup = supersetGroup,
        createdAt = STARTED_AT,
        updatedAt = STARTED_AT,
    )

fun workoutSet(
    id: Long,
    sessionExerciseId: Long,
    setNumber: Int,
    reps: Int? = null,
    weight: Long? = null,
    isCompleted: Boolean = true,
) = WorkoutSet(
    id = id,
    sessionExerciseId = sessionExerciseId,
    setNumber = setNumber,
    reps = reps,
    weight = weight,
    completedAt = if (isCompleted) FINISHED_AT else 0L,
    isCompleted = isCompleted,
    createdAt = STARTED_AT,
    updatedAt = STARTED_AT,
)

fun record(
    exerciseId: Long,
    workoutSetId: Long,
    type: PrType = PrType.MAX_WEIGHT,
    weight: Long? = 80_000_000,
) = PersonalRecord(
    id = workoutSetId,
    exerciseId = exerciseId,
    workoutSetId = workoutSetId,
    type = type,
    weight = weight,
    achievedAt = FINISHED_AT,
)
