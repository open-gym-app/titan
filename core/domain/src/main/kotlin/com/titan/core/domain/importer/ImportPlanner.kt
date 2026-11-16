package com.titan.core.domain.importer

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.superset.Supersets
import com.titan.core.domain.validation.BodyMeasurementValidator
import com.titan.core.domain.validation.SessionEffortValidator
import com.titan.core.domain.validation.SetValidator

/**
 * Decides what an [ImportedHistory] becomes on this device: the one place an import applies
 * Titan's rules, for every source at once.
 *
 * PERSISTENCE.md lists what the schema does not hold and the domain does — set bounds, dense set
 * numbering, the superset rules, the all-null measurement, the enum-like columns — and says an
 * importer has to decide what to do about each. The backup restore preserves them verbatim,
 * because a backup is Titan's own data. This import is the opposite case: the data is another
 * app's, nothing vouches for it, and what it is promised to become is a history indistinguishable
 * from one logged here. So it holds the data to exactly what logging holds it to, and leaves out
 * what it cannot hold — counted in [ImportSkips], never silently.
 *
 * ### Repaired rather than refused
 *
 * Where the source's value has an obvious reading in Titan's terms, it is read that way rather
 * than costing the set:
 *
 * - an RPE outside `10..100`, a negative rest, a non-positive heart rate: not recorded;
 * - a climb or a step count on a set that is not cardio, or out of `SetValidator`'s bounds, and
 *   a workout's calories or rating out of `SessionEffortValidator`'s: not recorded. They describe
 *   the set or the workout; they are not what makes it one;
 * - zero reps or a zero duration: not recorded, which leaves the set valid if its exercise is not
 *   logged by that dimension, and invalid if it is;
 * - a negative weight: not recorded. Several apps write an assisted pull-up's assistance as a
 *   negative load, and Titan has no column for assistance; the reps are still the set;
 * - a zero distance, or a zero weight on an exercise not logged by reps: not recorded. Exporters
 *   fill every column of every row, and "0 kg" on a run is a column, not a measurement. A zero
 *   weight on a rep-counted exercise is kept: that is a bodyweight set;
 * - a superset grouping that breaks the rules: [Supersets.tear], the repair a reorder uses;
 * - a workout with no duration, or an implausible one: [ESTIMATED_SECONDS_PER_SET] per set;
 * - a workout that would finish in the future: finished now.
 *
 * ### Refused
 *
 * A set `SetValidator` refuses after those repairs; a workout left with no sets, or that started
 * before 1970 — the weekly analytics cannot bucket one, see PERSISTENCE.md — or after now; a
 * template with no name or no lines; a measurement `BodyMeasurementValidator` refuses or dated
 * outside the same window.
 *
 * ### Duplicates
 *
 * A workout that started at the same millisecond as a session on the device, or as an earlier
 * workout in the file, is the same workout; a measurement taken at the same millisecond likewise.
 * Millisecond equality is deliberately the whole test: two real workouts never share one, and
 * anything looser (same day, same name) would merge two sessions somebody genuinely did. A
 * template is a duplicate only when one of the same name plans the same exercises in the same
 * order; one that merely shares a name — with a default template, say — is imported under a
 * numbered name instead, since a template's name is its identity in every list that shows it.
 *
 * ### Set timestamps
 *
 * No source this was designed against records when each set was finished, only when the workout
 * started and how long it lasted. A set's `completed_at` is therefore spread evenly across the
 * workout in the order the sets were performed, the last one at the finish: a record's date is
 * the day it was set, the sets keep their order, and the volume record anchors to the last set
 * as it does for a logged session.
 */
object ImportPlanner {

    /**
     * Two minutes a set, for a workout whose source records no duration: the thirty seconds of work
     * and ninety of rest `TemplateDuration` prices a set at with the app's default rest.
     */
    const val ESTIMATED_SECONDS_PER_SET = 120L

    /**
     * Past a day, a recorded duration is a timer somebody forgot to stop, not a workout, and an
     * estimate is the closer reading.
     */
    const val MAX_WORKOUT_SECONDS = 24L * 60 * 60

    private const val MILLIS_PER_SECOND = 1_000L

    private val CALORIES = SessionEffortValidator.CALORIES_MIN..SessionEffortValidator.CALORIES_MAX
    private val RATINGS = SessionEffortValidator.RATING_MIN..SessionEffortValidator.RATING_MAX

    /** Plans [history] against [baseline]. Pure, and never throws for anything [history] holds. */
    fun plan(
        history: ImportedHistory,
        baseline: ImportBaseline,
    ): ImportPlan = Planning(history, baseline).run()

    /**
     * How an exercise is logged, read off the shapes of every set recorded against it.
     *
     * Reps win if at least as many sets carry them as are timed without them, because a
     * rep-counted movement with the occasional timed set is far commoner than the reverse, and
     * a tie has no better evidence. Otherwise the sets are timed: a distance on any of them
     * makes it [ExerciseType.ENDURANCE], none makes it [ExerciseType.ISOMETRIC]. No evidence at
     * all reads as [ExerciseType.STRENGTH], the catalogue's own default.
     */
    internal fun inferType(shapes: List<SetShape>): ExerciseType {
        val counted = shapes.count { it.reps.isPositive() }
        val timed = shapes.count { !it.reps.isPositive() && it.duration.isPositive() }
        return when {
            counted >= timed -> ExerciseType.STRENGTH
            shapes.any { it.distance.isPositive() } -> ExerciseType.ENDURANCE
            else -> ExerciseType.ISOMETRIC
        }
    }

    /** The dimensions a set carries, whatever it is a set of. */
    internal data class SetShape(
        val reps: Int?,
        val duration: Long?,
        val distance: Long?,
    )

    private class Planning(
        private val history: ImportedHistory,
        private val baseline: ImportBaseline,
    ) {
        private val now = baseline.nowMillis
        private val matcher = ExerciseMatcher(baseline.exercises)
        private val types: Map<ImportedExercise, ExerciseType> = inferTypes()

        private val refs = HashMap<ImportedExercise, ExerciseRef?>()
        private val newExercises = mutableListOf<NewExercise>()
        private val newByIdentity = HashMap<Triple<String, EquipmentType, ExerciseType>, Int>()

        private var skips = ImportSkips()

        fun run(): ImportPlan {
            val workouts = planWorkouts()
            val templates = planTemplates()
            // After the workouts and templates, so an exercise they name is already resolved and
            // the library entry for it resolves to the same thing.
            history.exercises.forEach(::resolve)
            val measurements = planMeasurements()
            val photos = planPhotos()

            return ImportPlan(
                newExercises = newExercises.toList(),
                workouts = workouts,
                templates = templates,
                measurements = measurements,
                matchedExercises = refs.values.filterIsInstance<ExerciseRef.Existing>().distinct().size,
                skipped = skips,
                photos = photos,
            )
        }

        // ------------------------------------------------------------------ exercises

        private fun inferTypes(): Map<ImportedExercise, ExerciseType> {
            val shapes = HashMap<ImportedExercise, MutableList<SetShape>>()
            history.workouts.forEach { workout ->
                workout.exercises.forEach { line ->
                    shapes.getOrPut(line.exercise, ::mutableListOf) +=
                        line.sets.map { SetShape(it.reps, it.duration, it.distance) }
                }
            }
            history.templates.forEach { template ->
                template.exercises.forEach { line ->
                    shapes.getOrPut(line.exercise, ::mutableListOf) +=
                        line.sets.map { SetShape(it.reps, it.duration, it.distance) }
                }
            }
            history.exercises.forEach { shapes.getOrPut(it, ::mutableListOf) }
            return shapes.mapValues { (exercise, sets) -> exercise.type ?: inferType(sets) }
        }

        /** Every exercise the history names is a key of [types], which already prefers its own. */
        private fun typeOf(exercise: ImportedExercise): ExerciseType = types.getValue(exercise)

        /**
         * The exercise [imported] resolves to, decided once per source exercise; null when it
         * matches nothing and has no usable name to create one under.
         */
        private fun resolve(imported: ImportedExercise): ExerciseRef? {
            if (imported in refs) return refs[imported]
            val type = typeOf(imported)
            val ref = matcher.match(imported, type)?.let { ExerciseRef.Existing(it.id) } ?: create(imported, type)
            refs[imported] = ref
            return ref
        }

        /**
         * A new custom exercise for [imported], or the one already planned for the same name,
         * equipment and type — two source keys that differ only in a hint are one exercise, and
         * the first to be resolved supplies the hints.
         *
         * Created for every exercise the source names, including one whose every set was
         * refused and one only its library lists: the user had it in the other app, and finding
         * it missing here would be a loss, where an exercise with no history is just a row in
         * the library.
         */
        private fun create(
            imported: ImportedExercise,
            type: ExerciseType,
        ): ExerciseRef.New? {
            val name = cleanName(imported.name) ?: return null
            // Never NULL, for the reason `EquipmentType.OTHER` documents: the unique index stops
            // deduplicating a column that is.
            val equipment = imported.equipment ?: EquipmentType.OTHER
            val identity = Triple(ExerciseMatcher.normalised(name), equipment, type)
            val index = newByIdentity.getOrPut(identity) {
                newExercises += NewExercise(
                    exercise = Exercise(
                        name = name,
                        type = type,
                        equipmentType = equipment,
                        // Bounded as `RestDurations` reads the column: anything outside it would
                        // be stored and then ignored by the timer.
                        defaultRest = imported.defaultRestSeconds
                            ?.takeIf { it in 1..UserPreferences.MAX_REST_DURATION.inWholeSeconds },
                        notes = cleanNotes(imported.notes),
                        isCustom = true,
                    ),
                    primaryMuscle = imported.primaryMuscle?.trim()?.let { hint ->
                        baseline.muscleGroups.firstOrNull { it.name.equals(hint, ignoreCase = true) }
                    },
                )
                newExercises.lastIndex
            }
            return ExerciseRef.New(index)
        }

        // ------------------------------------------------------------------ workouts

        private fun planWorkouts(): List<PlannedWorkout> {
            val starts = baseline.sessionStarts.toHashSet()
            val planned = history.workouts
                .sortedBy(ImportedWorkout::startedAt)
                .mapNotNull { workout ->
                    when {
                        workout.startedAt !in 0..now -> {
                            skips = skips.copy(invalidWorkouts = skips.invalidWorkouts + 1)
                            null
                        }

                        workout.startedAt in starts -> {
                            skips = skips.copy(duplicateWorkouts = skips.duplicateWorkouts + 1)
                            null
                        }

                        else -> planWorkout(workout)?.also { starts += it.startedAt } ?: run {
                            skips = skips.copy(invalidWorkouts = skips.invalidWorkouts + 1)
                            null
                        }
                    }
                }
            return planned.sortedWith(compareBy(PlannedWorkout::finishedAt, PlannedWorkout::startedAt))
        }

        /** [workout] as a session, or null when none of its sets survive. */
        private fun planWorkout(workout: ImportedWorkout): PlannedWorkout? {
            val lines = workout.exercises.mapNotNull(::planLine)
            if (lines.isEmpty()) return null

            val setCount = lines.sumOf { it.sets.size }
            val seconds = workout.durationSeconds?.takeIf { it in 1..MAX_WORKOUT_SECONDS }
                ?: (setCount * ESTIMATED_SECONDS_PER_SET)
            val finishedAt = minOf(workout.startedAt + seconds * MILLIS_PER_SECOND, now)
            val span = finishedAt - workout.startedAt

            var performed = 0
            val stamped = lines.map { line ->
                line.copy(
                    sets = line.sets.map { set ->
                        performed++
                        val at = workout.startedAt + span * performed / setCount
                        set.copy(completedAt = at, createdAt = at, updatedAt = at)
                    },
                )
            }
            val grouping = Supersets.tear(stamped.map(PlannedWorkoutExercise::supersetGroup))

            return PlannedWorkout(
                startedAt = workout.startedAt,
                finishedAt = finishedAt,
                name = WorkoutSession.normalizeName(workout.name),
                notes = cleanNotes(workout.notes),
                exercises = stamped.mapIndexed { index, line -> line.copy(supersetGroup = grouping[index]) },
                calories = workout.calories?.takeIf { it in CALORIES },
                rating = workout.rating?.takeIf { it in RATINGS },
            )
        }

        /**
         * One exercise of a workout with its valid sets numbered from 1, or null when it has
         * none. Timestamps are left for [planWorkout], which is what knows the whole workout.
         */
        private fun planLine(line: ImportedWorkoutExercise): PlannedWorkoutExercise? {
            val ref = resolve(line.exercise)
            if (ref == null) {
                skips = skips.copy(invalidSets = skips.invalidSets + line.sets.size)
                return null
            }
            val type = typeOf(line.exercise)
            val sets = line.sets
                .map { it.toWorkoutSet(type) }
                .filter { set ->
                    SetValidator.validate(set, type).isValid.also { valid ->
                        if (!valid) skips = skips.copy(invalidSets = skips.invalidSets + 1)
                    }
                }
                .mapIndexed { index, set -> set.copy(setNumber = index + 1) }
            if (sets.isEmpty()) return null

            return PlannedWorkoutExercise(
                exercise = ref,
                notes = cleanNotes(line.notes),
                supersetGroup = line.supersetGroup,
                sets = sets,
            )
        }

        /** The set as a row to validate, with the repairs the class doc lists applied. */
        private fun ImportedSet.toWorkoutSet(type: ExerciseType): WorkoutSet =
            WorkoutSet(
                sessionExerciseId = 0,
                setNumber = 0,
                reps = reps.takeIfPositive(),
                weight = weight?.takeIf { it > 0L || (it == 0L && type == ExerciseType.STRENGTH) },
                duration = duration.takeIfPositive(),
                distance = distance.takeIfPositive(),
                heartRateAvg = heartRateAvg.takeIfPositive(),
                heartRateMax = heartRateMax.takeIfPositive(),
                elevationGain = elevationGain
                    ?.takeIf { type == ExerciseType.ENDURANCE }
                    ?.takeIf { it in SetValidator.ELEVATION_GAIN_MIN_CM..SetValidator.ELEVATION_GAIN_MAX_CM },
                steps = steps
                    ?.takeIf { type == ExerciseType.ENDURANCE }
                    ?.takeIf { it in SetValidator.STEPS_MIN..SetValidator.STEPS_MAX },
                restAfter = restAfter?.takeIf { it >= 0L },
                rpe = rpe?.takeIf { it in WorkoutSet.RPE_MIN..WorkoutSet.RPE_MAX },
                setType = setType,
                completedAt = 0,
                isCompleted = true,
                notes = cleanNotes(notes),
                createdAt = 0,
                updatedAt = 0,
            )

        // ------------------------------------------------------------------ templates

        private fun planTemplates(): List<PlannedTemplate> {
            val taken = baseline.templates.mapTo(HashSet()) { it.name.trim().lowercase() }
            val existing = baseline.templates.mapTo(
                HashSet(),
            ) { identity(it.name, it.exerciseIds.map(ExerciseRef::Existing)) }
            val planned = mutableListOf<PlannedTemplate>()

            history.templates.forEach { template ->
                val name = cleanName(template.name)
                val lines = template.exercises.mapNotNull { line ->
                    resolve(line.exercise)?.let { ref ->
                        PlannedTemplateExercise(
                            exercise = ref,
                            sets = line.sets.map(::sanitized),
                            notes = cleanNotes(line.notes),
                            supersetGroup = line.supersetGroup,
                        )
                    }
                }
                when {
                    name == null || lines.isEmpty() ->
                        skips = skips.copy(invalidTemplates = skips.invalidTemplates + 1)

                    !existing.add(identity(name, lines.map(PlannedTemplateExercise::exercise))) ->
                        skips = skips.copy(duplicateTemplates = skips.duplicateTemplates + 1)

                    else -> {
                        val unique = uniqueName(name, taken)
                        taken += unique.lowercase()
                        val grouping = Supersets.tear(lines.map(PlannedTemplateExercise::supersetGroup))
                        planned += PlannedTemplate(
                            name = unique,
                            exercises = lines.mapIndexed { index, line -> line.copy(supersetGroup = grouping[index]) },
                        )
                    }
                }
            }
            return planned
        }

        private fun identity(
            name: String,
            exercises: List<ExerciseRef>,
        ): Pair<String, List<ExerciseRef>> = name.trim().lowercase() to exercises

        /** [name], or `name (2)`, `name (3)`... — the first no template already carries. */
        private fun uniqueName(
            name: String,
            taken: Set<String>,
        ): String =
            generateSequence(1) { it + 1 }
                .map { n -> if (n == 1) name else "$name ($n)" }
                .first { it.lowercase() !in taken }

        /** A planned set with every figure outside `SetValidator`'s bounds left unplanned. */
        private fun sanitized(set: PlannedSet): PlannedSet =
            set.copy(
                reps = set.reps?.takeIf { it in SetValidator.REPS_MIN..SetValidator.REPS_MAX },
                weight = set.weight?.takeIf { it in SetValidator.WEIGHT_MIN_MG..SetValidator.WEIGHT_MAX_MG },
                duration = set.duration
                    ?.takeIf { it in SetValidator.DURATION_MIN_SECONDS..SetValidator.DURATION_MAX_SECONDS },
                distance = set.distance?.takeIf { it in SetValidator.DISTANCE_MIN_CM..SetValidator.DISTANCE_MAX_CM },
            )

        // ------------------------------------------------------------------ photos

        /**
         * Photos dated inside the import's window, oldest first, without one already on the device
         * or earlier in the file at the same millisecond — the measurement rule, for the same
         * reason. Whether the bytes are an image is the store's to find out, when it reads them.
         */
        private fun planPhotos(): List<ImportedPhoto> {
            val times = baseline.photoTimes.toHashSet()
            return history.photos
                .sortedBy(ImportedPhoto::takenAt)
                .filter { photo ->
                    when {
                        photo.takenAt !in 0..now -> {
                            skips = skips.copy(invalidPhotos = skips.invalidPhotos + 1)
                            false
                        }

                        !times.add(photo.takenAt) -> {
                            skips = skips.copy(duplicatePhotos = skips.duplicatePhotos + 1)
                            false
                        }

                        else -> true
                    }
                }
        }

        // ------------------------------------------------------------------ measurements

        private fun planMeasurements(): List<BodyMeasurement> {
            val times = baseline.measurementTimes.toHashSet()
            return history.bodyMeasurements
                .sortedBy(BodyMeasurement::createdAt)
                .mapNotNull { source ->
                    val entry = source.copy(id = 0, notes = cleanNotes(source.notes), updatedAt = source.createdAt)
                    when {
                        entry.createdAt !in 0..now || !BodyMeasurementValidator.validate(entry).isValid -> {
                            skips = skips.copy(invalidMeasurements = skips.invalidMeasurements + 1)
                            null
                        }

                        !times.add(entry.createdAt) -> {
                            skips = skips.copy(duplicateMeasurements = skips.duplicateMeasurements + 1)
                            null
                        }

                        else -> entry
                    }
                }
        }
    }

    private val CONTROL_OR_SPACE = Regex("[\\p{Cntrl}\\s]+")

    /** A name as stored: control characters and runs of whitespace collapsed to one space, trimmed. */
    private fun cleanName(raw: String): String? = raw.replace(CONTROL_OR_SPACE, " ").trim().takeIf(String::isNotEmpty)

    /** Free text as stored: trimmed, and blank collapsed to null, as every notes column reads it. */
    private fun cleanNotes(raw: String?): String? = raw?.trim()?.takeIf(String::isNotEmpty)

    private fun Int?.isPositive(): Boolean = this != null && this > 0

    private fun Long?.isPositive(): Boolean = this != null && this > 0L

    private fun Int?.takeIfPositive(): Int? = this?.takeIf { it > 0 }

    private fun Long?.takeIfPositive(): Long? = this?.takeIf { it > 0L }
}
