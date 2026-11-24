package com.titan.core.domain.importer

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SetType
import com.titan.core.domain.repository.PhotoSource

/**
 * A training history as another app exported it, translated into Titan's units and nothing else.
 *
 * This is the one shape every source parser produces and the only one [ImportPlanner] reads, so
 * a parser for a new app is a function from that app's bytes to this and owes nothing else. What
 * it deliberately does **not** carry is anything Titan derives: no ids, no set numbers, no
 * personal records, no XP. Records in particular are always recomputed from the sets, because a
 * record row has to point at the set that achieved it, and because every app judges a record by
 * its own rules — a history whose records disagreed with what its sets show would not be
 * indistinguishable from one logged here.
 *
 * Every list may be empty. Most apps export workouts alone, and a source with no body
 * measurements or no templates is the ordinary case rather than a malformed one.
 *
 * ### Units are Titan's
 *
 * The parser converts, once, into the integers PERSISTENCE.md lists: weight in milligrams,
 * distance in centimetres, durations in seconds, circumferences in millimetres, timestamps in
 * epoch milliseconds, RPE times ten. A parser is the only code that knows whether a source wrote
 * pounds or kilograms, so it is the only place the conversion can be right.
 */
data class ImportedHistory(
    /**
     * The source's exercise library, for the exercises it holds that no workout or template
     * names — a custom exercise the user set up and has not trained yet. Every exercise a
     * workout or template names is imported whether or not it is listed here; listing it as well
     * is harmless, and is how a parser hands over details only the library has, such as notes.
     */
    val exercises: List<ImportedExercise> = emptyList(),
    val workouts: List<ImportedWorkout> = emptyList(),
    val templates: List<ImportedTemplate> = emptyList(),
    /**
     * Body measurements, as rows Titan stores directly — every site the body screen records, so a
     * parser fills whichever of them its source tracks and the rest stay null. `id` and
     * `updatedAt` are ignored:
     * the store assigns the first, and the second is stamped with [BodyMeasurement.createdAt],
     * which is when the measurement was taken.
     */
    val bodyMeasurements: List<BodyMeasurement> = emptyList(),
    /** Progress photos, for the sources whose export carries the images themselves. */
    val photos: List<ImportedPhoto> = emptyList(),
) {
    /** Whether the source held nothing an import could write. */
    val isEmpty: Boolean
        get() = exercises.isEmpty() && workouts.isEmpty() && templates.isEmpty() &&
            bodyMeasurements.isEmpty() && photos.isEmpty()
}

/**
 * One progress photo from the source.
 *
 * @property takenAt when it was taken, as the source declares it. This is the time stored, even
 *   when the image records a different one, because it is also how a photo already imported is
 *   recognised, as a workout is by its start, and only the declared time is there for every photo.
 * @property source the image's bytes, opened afresh on every read; see [PhotoSource].
 */
data class ImportedPhoto(
    val takenAt: Long,
    val source: PhotoSource,
)

/**
 * An exercise as the source names it, with whatever the source says about how it is performed.
 *
 * Used as a *key*: two references with equal fields are the same exercise, so a parser builds one
 * per distinct exercise in its file and every workout and template line refers to it. Resolving
 * it to a catalogue row, or creating a custom one, is [ExerciseMatcher]'s job and happens once
 * per key, not once per set.
 *
 * @property name what the source calls it, and the name a custom exercise is created under
 *   when nothing in the catalogue matches.
 * @property catalogueName the Titan catalogue exercise this is known to be, when the parser has a
 *   mapping table for its source — Strong's "Bench Press (Barbell)" is the catalogue's "Barbell
 *   Bench Press - Medium Grip", and no amount of string normalising finds that. Tried before
 *   [name].
 * @property equipment what the source says it is performed with, used to tell apart catalogue
 *   rows of the same name and stored on a custom exercise. Null when the source does not say.
 * @property type how the exercise is logged, when the source says. Null lets the planner infer it
 *   from the sets — see [ImportPlanner.inferType] — which is what most sources need.
 * @property primaryMuscle the muscle group the source files the exercise under, matched without
 *   regard to case against the catalogue's `muscle_group.name` (`CHEST`, `LOWER_BACK`...).
 * @property notes the user's own remarks on the exercise.
 * @property defaultRestSeconds the rest the source's timer ran after this exercise.
 *
 * [primaryMuscle], [notes] and [defaultRestSeconds] are only written onto a custom exercise the
 * import creates. An exercise already on the device keeps what it has: those columns are the
 * user's, and an import that overwrote them would be editing data it was not asked to touch.
 */
data class ImportedExercise(
    val name: String,
    val catalogueName: String? = null,
    val equipment: EquipmentType? = null,
    val type: ExerciseType? = null,
    val primaryMuscle: String? = null,
    val notes: String? = null,
    val defaultRestSeconds: Long? = null,
)

/**
 * One finished workout.
 *
 * @property startedAt when it began, in epoch milliseconds. Also how a workout already on the
 *   device is recognised: a session that started at the same millisecond is taken to be this
 *   one, so importing a file twice writes it once.
 * @property durationSeconds how long it lasted, when the source records it. Null asks the planner
 *   to estimate one from the sets — see [ImportPlanner.ESTIMATED_SECONDS_PER_SET].
 * @property exercises in the order they were performed.
 * @property calories what the workout burned, in kilocalories, when the source records it.
 * @property rating how the workout went, 1 to 5, when the source rates workouts — a parser maps
 *   its own scale onto Titan's five stars.
 */
data class ImportedWorkout(
    val startedAt: Long,
    val durationSeconds: Long? = null,
    val name: String? = null,
    val notes: String? = null,
    val exercises: List<ImportedWorkoutExercise>,
    val calories: Int? = null,
    val rating: Int? = null,
)

/**
 * One exercise of a workout, and the sets performed of it, in order.
 *
 * @property supersetGroup the source's own superset key, if it has one. Only equality matters:
 *   exercises sharing a value were performed as one superset. The planner repairs a grouping that
 *   breaks Titan's superset rules rather than refusing the workout over it.
 */
data class ImportedWorkoutExercise(
    val exercise: ImportedExercise,
    val sets: List<ImportedSet>,
    val notes: String? = null,
    val supersetGroup: Int? = null,
)

/**
 * One performed set. Every set a source exports is taken to have been done — a planned set that
 * was never ticked off is not history, and no source this was designed against exports one.
 *
 * Units as [ImportedHistory] lists them. [rpe] is RPE times ten; a value outside
 * `10..100` is read as not recorded rather than refusing the set. [elevationGain] is in
 * centimetres and [steps] a count; both describe a piece of cardio and are kept on endurance sets
 * only.
 */
data class ImportedSet(
    val setType: SetType = SetType.NORMAL,
    val reps: Int? = null,
    val weight: Long? = null,
    val duration: Long? = null,
    val distance: Long? = null,
    val rpe: Int? = null,
    val heartRateAvg: Int? = null,
    val heartRateMax: Int? = null,
    val elevationGain: Long? = null,
    val steps: Int? = null,
    val restAfter: Long? = null,
    val notes: String? = null,
)

/** A saved routine, as the source plans it. */
data class ImportedTemplate(
    val name: String,
    val exercises: List<ImportedTemplateExercise>,
)

/** One line of a routine: which exercise, and the sets it plans. */
data class ImportedTemplateExercise(
    val exercise: ImportedExercise,
    val sets: List<PlannedSet> = emptyList(),
    val notes: String? = null,
    val supersetGroup: Int? = null,
)
