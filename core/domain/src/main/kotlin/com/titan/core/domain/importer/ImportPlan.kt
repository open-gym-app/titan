package com.titan.core.domain.importer

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.WorkoutSet

/**
 * Exactly what an import will write, worked out before anything is.
 *
 * [ImportPlanner] turns an [ImportedHistory] into this against what the device already holds, and
 * [com.titan.core.domain.usecase.ImportHistoryUseCase] writes it without making another decision.
 * Splitting the two is what lets every rule about *what* lands — the bounds, the duplicates, the
 * exercise matching, the timestamps — be tested as a pure function, and leaves the writer nothing
 * to get wrong but the order of its inserts.
 *
 * Everything in here already satisfies Titan's invariants: every set passes `SetValidator` for
 * its exercise's type and is numbered densely from 1, every superset grouping is well formed,
 * every measurement passes `BodyMeasurementValidator`, and no workout, measurement or template
 * duplicates one on the device or another in the plan.
 *
 * @property newExercises custom exercises to create, referred to by [ExerciseRef.New.index].
 * @property workouts in the order they finished, which is the order they are replayed in.
 * @property matchedExercises how many distinct source exercises resolved to one already on the
 *   device rather than to a new one.
 */
data class ImportPlan(
    val newExercises: List<NewExercise>,
    val workouts: List<PlannedWorkout>,
    val templates: List<PlannedTemplate>,
    val measurements: List<BodyMeasurement>,
    val matchedExercises: Int,
    val skipped: ImportSkips,
    /** Photos to store, oldest first, after the history itself has committed. */
    val photos: List<ImportedPhoto> = emptyList(),
) {
    /** How many sets [workouts] hold between them. */
    val setCount: Int get() = workouts.sumOf(PlannedWorkout::setCount)
}

/** Which exercise a planned line is: one on the device, or one the import creates. */
sealed interface ExerciseRef {

    data class Existing(val id: Long) : ExerciseRef

    /** The [index]th of [ImportPlan.newExercises]. */
    data class New(val index: Int) : ExerciseRef
}

/**
 * A custom exercise to create, and the muscle group to link it to as its prime mover, if the
 * source named one the catalogue has.
 */
data class NewExercise(
    val exercise: Exercise,
    val primaryMuscle: MuscleGroup?,
)

/**
 * A workout to write as a finished session.
 *
 * @property finishedAt the session's `completed_at`, and the moment the completion pipeline is
 *   told it finished — so its records, its streak week and its quest period are the ones it was
 *   trained in.
 */
data class PlannedWorkout(
    val startedAt: Long,
    val finishedAt: Long,
    val name: String?,
    val notes: String?,
    val exercises: List<PlannedWorkoutExercise>,
    val calories: Int? = null,
    val rating: Int? = null,
) {
    val setCount: Int get() = exercises.sumOf { it.sets.size }
}

/**
 * One exercise of a [PlannedWorkout].
 *
 * @property sets complete, validated and numbered, each carrying its own `completedAt`. Their
 *   `sessionExerciseId` is 0, because the row they belong to does not exist yet; the writer fills
 *   it in. The planner's list builds each set from the parsed one as it is read rather than
 *   holding a copy, so a plan costs little more than the history it was made from: see
 *   [PlannedSets].
 */
data class PlannedWorkoutExercise(
    val exercise: ExerciseRef,
    val notes: String?,
    val supersetGroup: Int?,
    val sets: List<WorkoutSet>,
)

/** A template to create, under a name no template on the device already has. */
data class PlannedTemplate(
    val name: String,
    val exercises: List<PlannedTemplateExercise>,
)

/** One line of a [PlannedTemplate]. */
data class PlannedTemplateExercise(
    val exercise: ExerciseRef,
    val sets: List<PlannedSet>,
    val notes: String?,
    val supersetGroup: Int?,
)

/**
 * What the planner left out, and why — the part of a report that tells the user an import was
 * partial instead of letting them find out by scrolling.
 *
 * A *duplicate* is already on the device, or earlier in the same file: importing the same export
 * twice is the commonest way to reach one, and it writes nothing the second time. An *invalid*
 * entry broke a rule Titan holds every logged entry to and could not be repaired without
 * inventing data.
 *
 * @property invalidSets sets refused on their own — out of bounds, or missing the dimension their
 *   exercise is logged by. A workout left with none is counted in [invalidWorkouts] as well.
 */
data class ImportSkips(
    val duplicateWorkouts: Int = 0,
    val invalidWorkouts: Int = 0,
    val invalidSets: Int = 0,
    val duplicateTemplates: Int = 0,
    val invalidTemplates: Int = 0,
    val duplicateMeasurements: Int = 0,
    val invalidMeasurements: Int = 0,
    val duplicatePhotos: Int = 0,
    val invalidPhotos: Int = 0,
) {
    /** Whether nothing was left out. */
    val isEmpty: Boolean get() = this == ImportSkips()
}

/**
 * What the device already holds that an import has to plan around.
 *
 * Read once, inside the transaction the import writes in, so that what the plan was checked
 * against is what it lands on.
 *
 * @property exercises every exercise, catalogue and custom, for [ExerciseMatcher].
 * @property muscleGroups the taxonomy, for a new exercise's prime mover.
 * @property sessionStarts every session's `created_at` — how a workout already imported is
 *   recognised.
 * @property measurementTimes every body measurement's `created_at`, for the same reason.
 * @property templates every template's name and exercise sequence.
 * @property photoTimes every progress photo's `taken_at`, for the duplicate check.
 * @property nowMillis the present: nothing may be imported as having happened after it.
 */
data class ImportBaseline(
    val exercises: List<Exercise>,
    val muscleGroups: List<MuscleGroup>,
    val sessionStarts: Set<Long>,
    val measurementTimes: Set<Long>,
    val templates: List<ExistingTemplate>,
    val nowMillis: Long,
    val photoTimes: Set<Long> = emptySet(),
)

/** A template already on the device, reduced to what a duplicate is judged by. */
data class ExistingTemplate(
    val name: String,
    val exerciseIds: List<Long>,
)
