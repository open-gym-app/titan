@file:OptIn(ExperimentalCoroutinesApi::class)

package com.opengym.core.domain.integration

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.Tag
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.timer.advanceTimeTo
import com.opengym.core.domain.usecase.FakeSessionRepository
import com.opengym.core.domain.usecase.MutableClock
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.domain.validation.ValidationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

// The rig the two domain integration tests share.
//
// These tests compose the real use cases, calculators and validator against in-memory
// repositories — the seam the domain is defined at — rather than mocking anything
// inside :core:domain. What is faked here is only what the module declares and does
// not implement: the repository interfaces, the Clock, and the coroutine scheduler the
// rest timer sleeps on.

/**
 * In-memory [ExerciseRepository] over a fixed catalogue.
 *
 * The session use cases never touch it — sets carry a `sessionExerciseId`, not an
 * exercise — but two things an integration run has to do need the catalogue: choosing
 * how a set is validated ([Exercise.type] is what [SetValidator] branches on) and
 * attributing volume to muscles. Both are lookups the UI layer will make, so the tests
 * make them the same way rather than hard-coding the answers.
 *
 * Only the reads those two need are backed by the catalogue; the rest of the interface
 * is present because the contract declares it.
 */
class FakeExerciseRepository(exercises: List<Exercise> = emptyList()) : ExerciseRepository {

    private val catalogue = MutableStateFlow(exercises.associateBy(Exercise::id))
    private val links = MutableStateFlow<Map<Long, List<ExerciseMuscleGroupLink>>>(emptyMap())
    private var nextId = (exercises.maxOfOrNull(Exercise::id) ?: 0L) + 1L

    override fun getAll(): Flow<List<Exercise>> = catalogue.map { it.values.toList() }

    override fun getById(id: Long): Flow<Exercise?> = catalogue.map { it[id] }

    override fun getByType(type: ExerciseType): Flow<List<Exercise>> =
        catalogue.map { stored -> stored.values.filter { it.type == type } }

    override fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>> =
        catalogue.map { stored -> stored.values.filter { it.equipmentType == equipmentType } }

    override fun searchByPartialName(query: String): Flow<List<Exercise>> =
        catalogue.map { stored -> stored.values.filter { it.name.contains(query, ignoreCase = true) } }

    /** Ordered primary first then by name, as the contract promises. */
    override fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>> =
        links.map { stored ->
            stored[exerciseId].orEmpty().sortedWith(primaryFirst)
        }

    /** The same links, keyed by exercise — the bulk read the exercise library does. */
    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> =
        links.map { stored -> stored.mapValues { (_, value) -> value.sortedWith(primaryFirst) } }

    /** Derived from the links, since this fake has no taxonomy table of its own. */
    override fun getMuscleGroups(): Flow<List<MuscleGroup>> = links.map { stored ->
        stored.values.flatten().map { it.muscleGroup }.distinct().sortedBy { it.name }
    }

    override fun getTags(exerciseId: Long): Flow<List<Tag>> = flowOf(emptyList())

    override suspend fun insert(exercise: Exercise): Long {
        val id = nextId++
        catalogue.value += id to exercise.copy(id = id)
        return id
    }

    override suspend fun insert(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ): Long {
        val id = insert(exercise)
        if (muscleGroups.isNotEmpty()) links.value += id to muscleGroups
        return id
    }

    override suspend fun update(exercise: Exercise) {
        catalogue.value += exercise.id to exercise
    }

    override suspend fun update(exercise: Exercise, muscleGroups: List<ExerciseMuscleGroupLink>) {
        update(exercise)
        // Replaced, not merged — the contract's word. Removing the entry rather than storing
        // an empty list keeps `getAllMuscleGroupLinks` free of empty groups, as the real one is.
        links.value = if (muscleGroups.isEmpty()) {
            links.value - exercise.id
        } else {
            links.value + (exercise.id to muscleGroups)
        }
    }

    override suspend fun delete(id: Long) {
        catalogue.value -= id
        links.value -= id
    }

    /** Records which muscle groups [exerciseId] trains, and how hard. */
    fun linkMuscles(exerciseId: Long, vararg muscleLinks: ExerciseMuscleGroupLink) {
        links.value += exerciseId to muscleLinks.toList()
    }

    /** The exercise [exerciseId] names, or a failure the caller can read as a fixture bug. */
    suspend fun exercise(exerciseId: Long): Exercise =
        getById(exerciseId).first() ?: throw AssertionError("exercise $exerciseId is not in the catalogue")
}

/** The order [ExerciseRepository.getMuscleGroupLinks] promises: primary movers, then by name. */
private val primaryFirst = compareByDescending<ExerciseMuscleGroupLink> { it.isPrimary }
    .thenBy { it.muscleGroup.name }

/**
 * Virtual time and the wall clock, advanced together.
 *
 * The two measure different things and both matter here: the rest timer counts down on
 * the [TestScope] scheduler, while the session's duration comes from
 * [com.opengym.core.domain.time.Clock] readings stamped onto the row. Advancing one
 * without the other would produce a run in which a 90-second rest cost the session no
 * time at all, which is not a session any assertion should be made about.
 *
 * Nothing sleeps for real: a whole workout of rests runs in microseconds.
 */
class Timeline(private val scope: TestScope, private val clock: MutableClock) {

    private var elapsed: Duration = Duration.ZERO

    /** Moves both clocks forward by [duration], running everything scheduled in between. */
    fun advance(duration: Duration) {
        elapsed += duration
        clock.advance(duration.inWholeMilliseconds)
        scope.advanceTimeTo(elapsed)
    }

    /** Wall-clock milliseconds the timeline has covered so far. */
    val elapsedMillis: Long get() = elapsed.inWholeMilliseconds
}

/**
 * Validates [set] against how [exerciseType] is logged and stores it, failing the test
 * on a set the validator would refuse.
 *
 * This is the composition the issue asks these tests to pin down: sets reach the store
 * through [SetValidator], not around it. `:core:domain` has no log-a-set use case to do
 * it for us — [com.opengym.core.domain.repository.SessionRepository] exposes no set
 * write at all, since M3 left logging to the screen that owns it — so the tests play
 * the caller's part, which is exactly the wiring a ViewModel will have to get right.
 */
fun FakeSessionRepository.logValidated(
    sessionExerciseId: Long,
    set: WorkoutSet,
    exerciseType: ExerciseType,
): WorkoutSet {
    val result = SetValidator.validate(set, exerciseType)
    if (result is ValidationResult.Invalid) {
        throw AssertionError("the validator refused a set this run expected to log: ${result.reason}")
    }
    return addSet(sessionExerciseId, set)
}

/** A completed set as the log-a-set screen would hand it over, before the store numbers it. */
fun completedSet(
    completedAt: Long,
    reps: Int? = null,
    weight: Long? = null,
    duration: Long? = null,
    distance: Long? = null,
    restAfter: Long? = null,
): WorkoutSet = WorkoutSet(
    // Filled in by the store; a caller that numbered its own sets would be one reorder
    // away from a duplicate (session_exercise_id, set_number).
    sessionExerciseId = 0,
    setNumber = 0,
    reps = reps,
    weight = weight,
    duration = duration,
    distance = distance,
    restAfter = restAfter,
    isCompleted = true,
    completedAt = completedAt,
    createdAt = completedAt,
    updatedAt = completedAt,
)

/** Kilograms in the canonical storage unit, so the fixtures read as the weights they are. */
fun kg(value: Long): Long = value * 1_000_000L

/** The catalogue the integration runs train against: one loaded lift and one timed hold. */
object Catalogue {

    val benchPress = Exercise(
        id = 1L,
        name = "Bench press",
        type = ExerciseType.STRENGTH,
        equipmentType = EquipmentType.BARBELL,
    )

    val plank = Exercise(
        id = 2L,
        name = "Plank",
        type = ExerciseType.ISOMETRIC,
        equipmentType = EquipmentType.BODYWEIGHT,
    )

    val chest = MuscleGroup(id = 1L, name = "Chest")
    val triceps = MuscleGroup(id = 2L, name = "Triceps")
    val abdominals = MuscleGroup(id = 3L, name = "Abdominals")

    /** A repository holding both exercises with their muscle links wired up. */
    fun repository(): FakeExerciseRepository = FakeExerciseRepository(listOf(benchPress, plank)).apply {
        linkMuscles(
            benchPress.id,
            ExerciseMuscleGroupLink(chest, isPrimary = true),
            ExerciseMuscleGroupLink(triceps, isPrimary = false),
        )
        linkMuscles(plank.id, ExerciseMuscleGroupLink(abdominals, isPrimary = true))
    }
}
