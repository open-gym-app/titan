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
import com.titan.core.domain.importer.RecordReading
import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceDurationUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.SuffixedExerciseNames
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
 *   `workout_time`, or at noon on the date when neither gives one, a spreadsheet's midnight counting
 *   as none ([startOf]). Wherever its rows are
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
    header: CsvHeader,
    private val context: ImportContext,
    private val figures: FigureColumns,
) : PlainCsvLayout {

    override val problem: String? = figures.problem

    private val cells = PlainCells(header)

    override fun date(record: List<String>): String? = cells[record, WORKOUT_DATE]

    override fun reading(order: DayMonthOrder?): RecordReading = WorkoutReading(order)

    /** One pass over the file's rows, building the workouts as it goes. */
    private inner class WorkoutReading(private val order: DayMonthOrder?) : RecordReading {

        private val names = SuffixedExerciseNames(SUFFIXED_CATALOGUE_NAMES, leadingEquipment = true)
        private val exercises = LinkedHashMap<ExerciseKey, ExerciseDraft>()
        private val workouts = LinkedHashMap<Long, WorkoutDraft>()

        /** The equipment each exercise name says, by name as written: resolving a name is not per-row work. */
        private val namedEquipment = HashMap<String, EquipmentType?>()

        private var rows = 0
        private var usable = 0

        override var setCount = 0
            private set

        override fun read(record: List<String>) {
            rows++
            val startedAt = startOf(cells[record, WORKOUT_DATE], cells[record, WORKOUT_TIME], context.zone, order)
                ?: return
            val name = cells[record, EXERCISE] ?: return
            usable++

            // Equipment the name already says is no other exercise: `Bench Press (Barbell)` with
            // `barbell` beside it on one row and nothing on the next is one exercise, not two.
            val equipment = cells[record, EQUIPMENT]?.let(::equipmentOf)
                ?.takeUnless { it == equipmentNamed(name) }
            val key = ExerciseKey(ExerciseMatcher.normalised(name), equipment)
            val exercise = exercises.getOrPut(key) { ExerciseDraft(name, key.equipment) }
            exercise.muscle = exercise.muscle ?: cells[record, MUSCLE]?.let(::muscleOf)

            val workout = workouts.getOrPut(startedAt) { WorkoutDraft(startedAt) }
            describe(workout, record)
            val line = workout.line(key, cells[record, SUPERSET]?.let(::supersetKey))
            SourceValues.note(cells[record, EXERCISE_NOTES])?.let(line.notes::add)
            set(record, exercise.assisted)?.let { set ->
                line.sets += set
                setCount++
            }
        }

        override fun result(): ParseResult =
            if (usable == 0 && rows > 0) {
                ParseResult.Malformed(
                    "none of the file's $rows rows has a workout_date and an exercise that can be read",
                )
            } else {
                ParseResult.Parsed(history())
            }

        private fun equipmentNamed(name: String): EquipmentType? =
            if (name in namedEquipment) {
                namedEquipment[name]
            } else {
                names.exercise(name).equipment.also { namedEquipment[name] = it }
            }

        private fun history(): ImportedHistory {
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
            workout.name = workout.name ?: cells[record, WORKOUT_NAME]
            workout.durationSeconds = workout.durationSeconds
                ?: figures.workoutDurationUnit.seconds(cells[record, figures.workoutDuration])
            workout.calories = workout.calories ?: whole(cells[record, WORKOUT_CALORIES])
            workout.rating = workout.rating ?: whole(cells[record, WORKOUT_RATING])
            SourceValues.note(cells[record, WORKOUT_NOTES])?.let(workout.notes::add)
        }

        /**
         * The set [record] describes; null when it records none of reps, weight, duration and
         * distance; and one with nothing recorded when one of those is not a number or is in a unit
         * Titan does not read, which the planner refuses and counts, where dropping the row here
         * would lose it without a word. An [assisted] exercise's weight is never read, so a
         * counterweight that does not read cannot cost the set its reps.
         */
        private fun set(
            record: List<String>,
            assisted: Boolean,
        ): ImportedSet? {
            val reps = Figure.of(cells[record, REPS], ::whole)
            val weight = Figure(
                cells[record, figures.weight],
                if (assisted) null else cells.weight(record, figures.weight, SourceWeightUnit.of(context.unitSystem)),
            )
            val duration = Figure.of(cells[record, figures.duration], figures.durationUnit::seconds)
            val distance = Figure.of(cells[record, figures.distance]) { text ->
                SourceValues.decimal(text)?.let { figure -> distanceUnit(record)?.toCentimetres(figure) }
            }
            val all = listOf(reps, weight, duration, distance)
            if (all.all(Figure<*>::isBlank)) return null

            val type = cells[record, SET_TYPE]?.let(::setTypeOf) ?: SetType.NORMAL
            val read = if (assisted) listOf(reps, duration, distance) else all
            if (read.any(Figure<*>::isUnreadable)) return ImportedSet(setType = type)
            return ImportedSet(
                setType = type,
                reps = reps.value,
                weight = weight.value,
                duration = duration.value,
                distance = distance.value,
                rpe = SourceValues.decimal(cells[record, RPE])?.let { (it * RPE_SCALE).roundToInt() },
                heartRateAvg = whole(cells[record, AVG_HEART_RATE]),
                heartRateMax = whole(cells[record, MAX_HEART_RATE]),
                restAfter = figures.restUnit.seconds(cells[record, figures.rest]),
                notes = SourceValues.note(cells[record, NOTES]),
            )
        }

        /**
         * The unit [record]'s distance is in: its row's, else the header's, else the user's long
         * distance. Null when its row names one Titan does not read.
         */
        private fun distanceUnit(record: List<String>): SourceDistanceUnit? {
            val label = cells[record, DISTANCE_UNIT]
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
    val isUnreadable: Boolean get() = SourceValues.isUnread(text, value)

    companion object {

        fun <T : Any> of(
            text: String?,
            read: (String) -> T?,
        ): Figure<T> = Figure(text, text?.let(read))
    }
}

/**
 * What tells one exercise of the file from another: its name, as [ExerciseMatcher.normalised] has
 * it, so `Bench press` and `bench press` are one, and the equipment `equipment` says when the name
 * does not already say it, so a row that names another is a different exercise.
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

    /** Whether its name calls it assisted, which leaves its weight out: see [SuffixedExerciseNames.namesAssistance]. */
    val assisted: Boolean = SuffixedExerciseNames.namesAssistance(name)

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
private fun equipmentOf(label: String): EquipmentType? =
    SuffixedExerciseNames.equipmentOf(label) ?: EQUIPMENT_NAMES[ExerciseMatcher.normalised(label)]

/** Titan's own equipment names, by [ExerciseMatcher.normalised] name: `ez curl bar`. */
private val EQUIPMENT_NAMES = EquipmentType.entries.associateBy { ExerciseMatcher.normalised(it.name) }

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
