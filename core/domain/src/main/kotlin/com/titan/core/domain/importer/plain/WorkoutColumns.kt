package com.titan.core.domain.importer.plain

import com.titan.core.domain.importer.DayMonthOrder
import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceDurationUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.WeightFigure
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.SetType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The format's workout file: one row per set, the columns [PlainCsvParser] lists.
 *
 * ### What becomes what
 *
 * - **A workout** is the rows that share a start: `workout_date` at its own time or at
 *   `workout_time`, or at noon on the date when neither gives one ([startOf]). Wherever its rows are
 *   in the file, they are one workout, and reading the file again gives the same starts, so a
 *   re-import is a duplicate of every workout. Two workouts on one day without a time share noon,
 *   and are one workout: apart, the planner would refuse the second as a duplicate of the first
 *   and lose its sets.
 * - **The workout's own columns** are repeated on each of its rows, and the first row that fills
 *   one is the one read. `workout_notes` is the exception: each different note is kept, once.
 * - **An exercise of the workout** is a run of consecutive rows naming the same exercise, so an
 *   exercise done twice with something between is two, in the order they happened. Inside a
 *   superset — consecutive rows sharing a `superset` label — rows are gathered by exercise instead,
 *   so a superset written set by set (A, B, A, B) is two exercises of two sets each.
 * - **A set** is a row with reps, a weight, a duration or a distance. A row with none of them names
 *   its exercise and nothing more: the exercise is imported, with no set.
 *
 * Exercise names go through [SuffixedExerciseNames] with the names Strong's and Hevy's libraries
 * share ([SUFFIXED_CATALOGUE_NAMES]) and the equipment read from the front of the name, because a
 * user converting another app's export brings that app's names, and those two conventions are
 * the common ones. A name from Titan's own library matches it as written. `equipment`, when it is
 * there, says the equipment instead.
 *
 * The weight of an exercise whose name calls it assisted is not imported, nor is a negative one:
 * either is a counterweight, which Titan has no column for, and as a load it would make the easiest
 * set the heaviest. See [SuffixedExerciseNames.namesAssistance].
 */
internal class WorkoutColumns private constructor(
    private val header: CsvHeader,
    private val context: ImportContext,
    private val figures: FigureColumns,
) : PlainCsvLayout {

    override val problem: String? = figures.problem

    override fun read(records: List<List<String>>): ParseResult {
        val order = SourceValues.dayMonthOrder(records.map { cell(it, WORKOUT_DATE) })
        val reading = WorkoutReading()
        val usable = records.count { reading.read(it, order) }
        return if (usable == 0 && records.isNotEmpty()) {
            ParseResult.Malformed(
                "none of the file's ${records.size} rows has a workout_date and an exercise that can be read",
            )
        } else {
            ParseResult.Parsed(reading.history())
        }
    }

    private fun cell(
        record: List<String>,
        name: String,
    ): String? = header.plainColumn(name)?.let { header[record, it] }

    private fun cell(
        record: List<String>,
        column: UnitColumn<*>?,
    ): String? = column?.let { header[record, it.name] }

    /** One pass over the file's rows, building the workouts as it goes. */
    private inner class WorkoutReading {

        private val names = SuffixedExerciseNames(SUFFIXED_CATALOGUE_NAMES, leadingEquipment = true)
        private val exercises = LinkedHashMap<ExerciseKey, ExerciseDraft>()
        private val workouts = LinkedHashMap<Long, WorkoutDraft>()

        /** Reads [record] into the history; whether it had a start and an exercise to read. */
        fun read(
            record: List<String>,
            order: DayMonthOrder?,
        ): Boolean {
            val startedAt = startOf(cell(record, WORKOUT_DATE), cell(record, WORKOUT_TIME), context.zone, order)
                ?: return false
            val name = cell(record, EXERCISE) ?: return false

            val key = ExerciseKey(ExerciseMatcher.normalised(name), cell(record, EQUIPMENT)?.let(::equipmentOf))
            val exercise = exercises.getOrPut(key) { ExerciseDraft(name, key.equipment) }
            exercise.muscle = exercise.muscle ?: cell(record, MUSCLE)?.let(::muscleOf)

            val workout = workouts.getOrPut(startedAt) { WorkoutDraft(startedAt) }
            describe(workout, record)
            val line = workout.line(key, cell(record, SUPERSET)?.let(::supersetKey))
            SourceValues.note(cell(record, EXERCISE_NOTES))?.let(line.notes::add)
            set(record, SuffixedExerciseNames.namesAssistance(name))?.let(line.sets::add)
            return true
        }

        fun history(): ImportedHistory {
            val resolved = exercises.mapValues { (_, draft) -> draft.toExercise(names) }
            return ImportedHistory(
                exercises = resolved.values.toList(),
                workouts = workouts.values.map { it.toWorkout(resolved) },
            )
        }

        /** Fills what [workout] does not know yet from [record]'s workout columns. */
        private fun describe(
            workout: WorkoutDraft,
            record: List<String>,
        ) {
            workout.name = workout.name ?: cell(record, WORKOUT_NAME)
            workout.durationSeconds = workout.durationSeconds
                ?: figures.workoutDurationUnit.seconds(cell(record, figures.workoutDuration))
            workout.calories = workout.calories ?: whole(cell(record, WORKOUT_CALORIES))
            workout.rating = workout.rating ?: whole(cell(record, WORKOUT_RATING))
            SourceValues.note(cell(record, WORKOUT_NOTES))?.let(workout.notes::add)
        }

        /**
         * The set [record] describes; null when it records none of reps, weight, duration and
         * distance; and one with nothing recorded when one of those is not a number or is in a unit
         * Titan does not read, which the planner refuses and counts, where dropping the row here
         * would lose it without a word.
         */
        private fun set(
            record: List<String>,
            assisted: Boolean,
        ): ImportedSet? {
            val reps = Figure.of(cell(record, REPS), ::whole)
            val weight = Figure.of(cell(record, figures.weight)) { text ->
                WeightFigure.parse(text)?.let { figure -> (figure.unit ?: weightUnit(record))?.let(figure::milligrams) }
            }
            val duration = Figure.of(cell(record, figures.duration), figures.durationUnit::seconds)
            val distance = Figure.of(cell(record, figures.distance)) { text ->
                SourceValues.decimal(text)?.let { figure -> distanceUnit(record)?.toCentimetres(figure) }
            }
            val all = listOf(reps, weight, duration, distance)
            if (all.all(Figure<*>::isBlank)) return null

            val type = cell(record, SET_TYPE)?.let(::setTypeOf) ?: SetType.NORMAL
            if (all.any(Figure<*>::isUnreadable)) return ImportedSet(setType = type)
            return ImportedSet(
                setType = type,
                reps = reps.value,
                weight = weight.value?.takeUnless { assisted },
                duration = duration.value,
                distance = distance.value,
                rpe = SourceValues.decimal(cell(record, RPE))?.let { (it * RPE_SCALE).roundToInt() },
                heartRateAvg = whole(cell(record, AVG_HEART_RATE)),
                heartRateMax = whole(cell(record, MAX_HEART_RATE)),
                restAfter = figures.restUnit.seconds(cell(record, figures.rest)),
                notes = SourceValues.note(cell(record, NOTES)),
            )
        }

        /**
         * The unit [record]'s weight is in when its cell names none: its row's, else the header's,
         * else the user's. Null when its row names one Titan does not read.
         */
        private fun weightUnit(record: List<String>): SourceWeightUnit? {
            val label = cell(
                record,
                WEIGHT_UNIT,
            ) ?: return figures.weight?.unit ?: SourceWeightUnit.of(context.unitSystem)
            return SourceWeightUnit.parse(label)
        }

        /**
         * The unit [record]'s distance is in: its row's, else the header's, else the user's long
         * distance. Null when its row names one Titan does not read.
         */
        private fun distanceUnit(record: List<String>): SourceDistanceUnit? {
            val label = cell(record, DISTANCE_UNIT)
                ?: return figures.distance?.unit ?: SourceDistanceUnit.of(context.unitSystem)
            return SourceDistanceUnit.parse(label)
        }
    }

    companion object {

        /** [header]'s layout, or null when it is not the format's workout file. */
        fun of(
            header: CsvHeader,
            context: ImportContext,
        ): WorkoutColumns? =
            if (header.plainColumn(WORKOUT_DATE) != null && header.plainColumn(EXERCISE) != null) {
                WorkoutColumns(header, context, FigureColumns.of(header))
            } else {
                null
            }
    }
}

/** The columns of a workout file whose figures are in a unit, and the unit each is in when its header names none. */
private class FigureColumns(
    val weight: UnitColumn<SourceWeightUnit>?,
    val distance: UnitColumn<SourceDistanceUnit>?,
    val duration: UnitColumn<SourceDurationUnit>?,
    val rest: UnitColumn<SourceDurationUnit>?,
    val workoutDuration: UnitColumn<SourceDurationUnit>?,
) {
    /** A set's time is counted in seconds, as a hold or an interval is. */
    val durationUnit: SourceDurationUnit = duration?.unit ?: SourceDurationUnit.SECOND

    val restUnit: SourceDurationUnit = rest?.unit ?: SourceDurationUnit.SECOND

    /** A workout's length is counted in minutes, as a person writes it down. */
    val workoutDurationUnit: SourceDurationUnit = workoutDuration?.unit ?: SourceDurationUnit.MINUTE

    val problem: String? = listOfNotNull(weight, distance, duration, rest, workoutDuration)
        .firstNotNullOfOrNull(UnitColumn<*>::problem)

    companion object {

        fun of(header: CsvHeader) =
            FigureColumns(
                weight = header.unitColumn(WEIGHT, SourceWeightUnit::parse),
                distance = header.unitColumn(DISTANCE, SourceDistanceUnit::parse),
                duration = header.unitColumn(DURATION, SourceDurationUnit::parse),
                rest = header.unitColumn(REST, SourceDurationUnit::parse),
                workoutDuration = header.unitColumn(WORKOUT_DURATION, SourceDurationUnit::parse),
            )
    }
}

/** One of a set's figures: its cell as written, and what it reads as, which is null when it does not read. */
private class Figure<T : Any>(
    private val text: String?,
    val value: T?,
) {
    val isBlank: Boolean get() = text == null

    /** Whether the cell says something that is not a figure: a set the planner should count rather than lose. */
    val isUnreadable: Boolean get() = text != null && value == null

    companion object {

        fun <T : Any> of(
            text: String?,
            read: (String) -> T?,
        ): Figure<T> = Figure(text, text?.let(read))
    }
}

/**
 * What tells one exercise of the file from another: its name, as [ExerciseMatcher.normalised] has
 * it, so `Bench press` and `bench press` are one, and the equipment `equipment` says, so a row
 * that names another is a different exercise.
 */
private data class ExerciseKey(
    val name: String,
    val equipment: EquipmentType?,
)

/** An exercise as the file names it, and the muscle the first row to say one gives it. */
private class ExerciseDraft(
    private val name: String,
    private val equipment: EquipmentType?,
) {
    var muscle: String? = null

    fun toExercise(names: SuffixedExerciseNames): ImportedExercise {
        val named = names.exercise(name)
        return named.copy(equipment = equipment ?: named.equipment, primaryMuscle = muscle)
    }
}

private class WorkoutDraft(val startedAt: Long) {

    var name: String? = null
    var durationSeconds: Long? = null
    var calories: Int? = null
    var rating: Int? = null
    val notes = LinkedHashSet<String>()
    private val lines = mutableListOf<LineDraft>()

    /** Where the superset the last line belongs to begins in [lines]. */
    private var supersetStart = 0
    private var supersets = 0

    /**
     * The exercise of this workout a row naming [exercise] in [superset] adds a set to: the last one,
     * when it is the same exercise and neither is in a superset; one of the superset the last line
     * is in, when the row is in it too; otherwise a new one.
     */
    fun line(
        exercise: ExerciseKey,
        superset: String?,
    ): LineDraft {
        val last = lines.lastOrNull()
        val existing = when {
            superset == null -> last?.takeIf { it.superset == null && it.exercise == exercise }

            last?.superset == superset -> lines.subList(
                supersetStart,
                lines.size,
            ).firstOrNull { it.exercise == exercise }

            else -> {
                supersetStart = lines.size
                supersets++
                null
            }
        }
        return existing ?: LineDraft(exercise, superset, supersets.takeIf { superset != null }).also(lines::add)
    }

    fun toWorkout(exercises: Map<ExerciseKey, ImportedExercise>) =
        ImportedWorkout(
            startedAt = startedAt,
            durationSeconds = durationSeconds,
            name = name,
            notes = notes.joinToString("\n").ifEmpty { null },
            exercises = lines.map { it.toLine(exercises.getValue(it.exercise)) },
            calories = calories,
            rating = rating,
        )
}

private class LineDraft(
    val exercise: ExerciseKey,
    val superset: String?,
    private val supersetGroup: Int?,
) {
    val sets = mutableListOf<ImportedSet>()
    val notes = LinkedHashSet<String>()

    fun toLine(exercise: ImportedExercise) =
        ImportedWorkoutExercise(
            exercise = exercise,
            sets = sets.toList(),
            notes = notes.joinToString("\n").ifEmpty { null },
            supersetGroup = supersetGroup,
        )
}

/** [text] as a whole number, rounded; null when it is blank or not a number. */
private fun whole(text: String?): Int? = SourceValues.decimal(text)?.roundToInt()

/**
 * A `superset` label as a key: `1` and `1.0`, as a spreadsheet re-saves a number, are one label,
 * and so are `A` and `a`.
 */
private fun supersetKey(label: String): String = SourceValues.decimal(label)?.toString() ?: label.lowercase(Locale.ROOT)

/**
 * The equipment [label] names: a word an exercise name would carry ([SuffixedExerciseNames.equipmentOf]
 * — `barbell`, `dumbbells`, `smith machine`, `ez bar`), or one of Titan's own equipment names
 * (`other`, `ez_curl_bar`); null for anything else, which leaves the exercise's name to say.
 */
private fun equipmentOf(label: String): EquipmentType? {
    val key = ExerciseMatcher.normalised(label)
    return SuffixedExerciseNames.equipmentOf(label)
        ?: EquipmentType.entries.firstOrNull { ExerciseMatcher.normalised(it.name) == key }
}

/**
 * A `muscle` cell as the name of one of the catalogue's muscle groups would be written, `Lower
 * back` as `LOWER_BACK`; null when nothing is left. The planner looks it up, and a name it does
 * not know leaves the exercise without one.
 */
private fun muscleOf(label: String): String? =
    ExerciseMatcher.normalised(label).replace(' ', '_').uppercase(Locale.ROOT).ifEmpty { null }

/** A `set_type` cell's type; normal for a blank and for a word the format does not define. */
private fun setTypeOf(label: String): SetType = SET_TYPES[ExerciseMatcher.normalised(label)] ?: SetType.NORMAL

/** `set_type` values, by [ExerciseMatcher.normalised] label: the words, the letters Strong uses, and Titan's names. */
private val SET_TYPES = mapOf(
    "normal" to SetType.NORMAL,
    "working" to SetType.NORMAL,
    "warmup" to SetType.WARMUP,
    "warm up" to SetType.WARMUP,
    "w" to SetType.WARMUP,
    "drop" to SetType.DROP_SET,
    "dropset" to SetType.DROP_SET,
    "drop set" to SetType.DROP_SET,
    "d" to SetType.DROP_SET,
    "failure" to SetType.FAILURE,
    "f" to SetType.FAILURE,
    "interval" to SetType.INTERVAL,
)

internal const val WORKOUT_DATE = "workout_date"
private const val WORKOUT_TIME = "workout_time"
private const val WORKOUT_NAME = "workout_name"
private const val WORKOUT_DURATION = "workout_duration"
private const val WORKOUT_NOTES = "workout_notes"
private const val WORKOUT_CALORIES = "workout_calories"
private const val WORKOUT_RATING = "workout_rating"
internal const val EXERCISE = "exercise"
private const val EQUIPMENT = "equipment"
private const val MUSCLE = "muscle"
private const val EXERCISE_NOTES = "exercise_notes"
private const val SUPERSET = "superset"
private const val SET_TYPE = "set_type"
private const val REPS = "reps"
internal const val WEIGHT = "weight"
internal const val WEIGHT_UNIT = "weight_unit"
private const val DURATION = "duration"
private const val DISTANCE = "distance"
private const val DISTANCE_UNIT = "distance_unit"
private const val RPE = "rpe"
private const val REST = "rest"
private const val AVG_HEART_RATE = "avg_heart_rate"
private const val MAX_HEART_RATE = "max_heart_rate"
internal const val NOTES = "notes"

/** RPE is written as it is said (`8.5`); Titan stores it times ten. */
private const val RPE_SCALE = 10
