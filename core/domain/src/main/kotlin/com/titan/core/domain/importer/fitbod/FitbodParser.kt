package com.titan.core.domain.importer.fitbod

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.RecordReading
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.csv.QualifiedColumn
import com.titan.core.domain.importer.readAll
import com.titan.core.domain.model.SetType
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Reads the workout history Fitbod exports: Log › the settings menu › Export Workout Data in the
 * app, which writes `WorkoutExport.csv`, and the extract Fitbod's support sends to users of the
 * Android app, which has no export of its own.
 *
 * ### The format
 *
 * Fitbod publishes no specification beyond where the button is, so what follows is what real
 * exports contain. One row per set, every column read by name:
 *
 * - **The app's export**: `Date, Exercise, Reps, Weight(kg), Duration(s), Distance(m), Incline,
 *   Resistance, isWarmup, Note, multiplier`. `Date` is the workout's start with its offset,
 *   `2023-09-21 10:48:11 +0000`, and every set of a workout carries the same one. Fitbod writes a
 *   space before some numbers, `0.0` in every column a set does not use, and `isWarmup` as `true`
 *   or `false`; workouts come newest first, their sets in the order they were performed.
 * - **The support extract**: `date, exercise_name, Reps, weight_kg, duration_seconds,
 *   distance_meters, Incline, Resistance, isWarmup`, with the day alone in `date` (`2025-06-23`),
 *   empty cells where the app writes zeros, and neither notes nor `multiplier`.
 * - **A spreadsheet's re-save** of either: `TRUE`/`FALSE`, numbers cut to fewer decimals, a byte
 *   order mark, CRLF. Nothing in it needs reading differently.
 *
 * The figures are in the units their headers name, which has been kilograms, seconds and metres in
 * every export seen whatever the user's own setting. A header that names no unit is read in the
 * user's, as [ImportContext.unitSystem] says. A time with no offset is read in
 * [ImportContext.zone], and a day with no time starts at [SourceValues.localDateAtNoon].
 *
 * #### Pounds written as kilograms
 *
 * A user who logs in pounds gets kilograms in the export anyway, converted with Fitbod's own
 * factor of 2.20462 pounds to the kilogram, so a 17.5 lb dumbbell arrives as `7.937875915123695`.
 * Stored as written, that is a few milligrams heavier than the 17.5 lb Titan stores for the same
 * dumbbell, enough to make two equal lifts compare unequal. So a figure that is a whole number of
 * hundredths of a pound once Fitbod's conversion is undone, and is not a whole number of
 * hundredths of a kilogram, is taken to be the pound figure the user typed; see [milligrams]. A
 * user who switched units keeps both kinds in one file, and each figure is read as what it is.
 *
 * #### `multiplier`
 *
 * How many times Fitbod counts the weight when it adds up a set's volume: `2` for a pair of
 * dumbbells or a movement done on each side, `4` for both, `1` for a barbell, a machine or
 * bodyweight, and `0` for exercises it does not count by weight — cardio, timed holds and the
 * assisted machines. The weight on the row is what one hand held, which is what a user logs a
 * dumbbell lift with in Titan and what Strong and Hevy export, so it is imported as written and
 * the multiplier is not applied. It is still evidence of one thing: Fitbod's older versions wrote
 * a hold's seconds in `Reps`, so a set with reps, a multiplier of `0` and no time, distance or
 * assistance is read as a hold of that many seconds rather than as a plank of sixty reps.
 *
 * ### What becomes what
 *
 * Fitbod has no workout id, name or end time. A workout is the rows that share an instant in
 * `Date`, wherever in the file they are: that is the instant Fitbod gives every set of one
 * workout, two workouts the same day have different ones, and reading the same file again gives
 * the same instants, so a re-import is a duplicate of every workout. A day without a time, from
 * the support extract, is one workout. Inside a workout, a run of consecutive rows naming one
 * exercise is one exercise of the workout, so a circuit that comes back to an exercise is two, in
 * the order they happened. `isWarmup` makes a set a warm-up. A `Note` is the exercise's note for
 * that workout, each distinct one kept once, because whether Fitbod attaches it to the set or the
 * exercise, it writes it on the rows of the exercise.
 *
 * Exercise names go through [SuffixedExerciseNames] with [FITBOD_CATALOGUE_NAMES] and the
 * equipment read from the front of the name, so "Barbell Bench Press" lands on the catalogue's
 * bench press as a barbell lift. The type is left to the planner to infer from the sets.
 *
 * ### What is deliberately not imported
 *
 * - **The weight of an assisted exercise.** Fitbod records the assistance ("Assisted Chin Up",
 *   40 lb), not a load; see [SuffixedExerciseNames.namesAssistance].
 * - **`Incline` and `Resistance`**, a treadmill's slope and a machine's level. Titan has nowhere
 *   to put them.
 * - **A workout's length.** The export has no end time; the planner estimates one from the sets.
 * - **Supersets and circuits as such.** The export does not mark which exercises were one.
 * - **Fitbod's recommendations, gym profiles and muscle recovery.** The export holds none of them.
 *
 * ### What a damaged file costs
 *
 * A header without a date, an exercise, `Reps`, `isWarmup` and a weight column is not a Fitbod
 * export at all, and is [ParseResult.NotRecognised]; `isWarmup` is what no other app writes. A
 * weight, distance or duration column whose header names something that is not a unit is
 * [ParseResult.Malformed], because no figure under it could be read. Past that, one bad row is
 * skipped rather than costing the history: a row with no date that reads or no exercise name is
 * left out, and a set whose weight, reps, distance or duration is not a number is passed on with
 * nothing recorded, so the planner refuses it and counts it in the report. An unreadable
 * `isWarmup` or `multiplier` alone costs nothing but what it would have said. Only a file whose
 * every row is unreadable, or whose CSV breaks in a way no row can be recovered from, is
 * [ParseResult.Malformed].
 */
class FitbodParser : HistoryParser {

    override fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult =
        source.open().use { stream ->
            val reader = CsvReader.open(stream)
            val layout = reader.readHeader()?.let { FitbodLayout.of(it, context) }
            val problem = layout?.problem
            when {
                layout == null -> ParseResult.NotRecognised
                problem != null -> ParseResult.Malformed(problem)
                else -> FitbodReading(layout).readAll(reader)
            }
        }

    internal companion object {

        /** Fitbod's own pounds to the kilogram, the factor its export converts with. */
        const val POUNDS_PER_KILOGRAM = 2.20462

        /**
         * [kilograms], as a Fitbod export writes a weight, in milligrams: the pound figure the
         * user typed when the kilograms are Fitbod's conversion of one, and the kilograms as
         * written otherwise.
         *
         * A figure that is a whole number of hundredths of a kilogram is a kilogram figure: a
         * pound figure converted by Fitbod's factor never is, short of half a tonne. Otherwise, a
         * figure within a hundred-thousandth of a pound of a whole number of hundredths of one,
         * once multiplied back, is that many pounds. The tolerance is far wider than the error in
         * the seventeen digits the app writes and the eight a spreadsheet keeps, and far narrower
         * than the gap between two hundredths, so a figure typed in kilograms with three or more
         * decimals is read as one unless it falls within it, and moves less than a gram if it does.
         */
        fun milligrams(kilograms: Double): Long {
            if (wholeHundredths(kilograms * HUNDREDTHS, KILOGRAM_TOLERANCE)) {
                return SourceWeightUnit.KILOGRAM.toMilligrams(kilograms)
            }
            val hundredthsOfPound = kilograms * POUNDS_PER_KILOGRAM * HUNDREDTHS
            return if (wholeHundredths(hundredthsOfPound, POUND_TOLERANCE)) {
                SourceWeightUnit.POUND.toMilligrams(hundredthsOfPound.roundToLong() / HUNDREDTHS)
            } else {
                SourceWeightUnit.KILOGRAM.toMilligrams(kilograms)
            }
        }

        private fun wholeHundredths(
            hundredths: Double,
            tolerance: Double,
        ): Boolean = abs(hundredths - Math.rint(hundredths)) < tolerance

        private const val HUNDREDTHS = 100.0

        /** In hundredths of a kilogram: floating-point noise on a figure like `1.1`, no more. */
        private const val KILOGRAM_TOLERANCE = 1e-6

        /** In hundredths of a pound: a hundred-thousandth of a pound. */
        private const val POUND_TOLERANCE = 1e-3
    }
}

/** The columns one Fitbod export has, and the units its figures are in. */
private class FitbodLayout(
    private val header: CsvHeader,
    val zone: ZoneId,
    val exerciseColumn: String,
    val weightColumn: String,
    val durationColumn: String?,
    val distanceColumn: String?,
    private val weightUnit: SourceWeightUnit?,
    val distanceUnit: SourceDistanceUnit?,
    /** Why no figure in one of the columns can be read, its header naming no unit Titan knows; null when none. */
    val problem: String?,
) {
    operator fun get(
        record: List<String>,
        column: String?,
    ): String? = column?.let { header[record, it] }

    /** [kilogramsOrPounds], read in this export's weight unit, in milligrams. */
    fun milligrams(kilogramsOrPounds: Double): Long =
        when (weightUnit) {
            SourceWeightUnit.POUND -> SourceWeightUnit.POUND.toMilligrams(kilogramsOrPounds)
            else -> FitbodParser.milligrams(kilogramsOrPounds)
        }

    companion object {

        /** [header]'s layout, or null when it is not a Fitbod export's. */
        fun of(
            header: CsvHeader,
            context: ImportContext,
        ): FitbodLayout? {
            val exercise = listOf(EXERCISE, EXERCISE_NAME).firstOrNull(header::contains)
            val weight = column(header, WEIGHT)
            if (exercise == null || weight == null || !header.containsAll(listOf(DATE, REPS, IS_WARMUP))) return null
            val duration = column(header, DURATION)
            val distance = column(header, DISTANCE)

            // A header that names no unit is in the user's; one that names something else is in none.
            val weightUnit = weight.qualifier
                ?.let(SourceWeightUnit::parse)
                ?: SourceWeightUnit.of(context.unitSystem).takeIf { weight.qualifier == null }
            val distanceUnit = distance?.qualifier
                ?.let(SourceDistanceUnit::parse)
                ?: SourceDistanceUnit.of(context.unitSystem).takeIf { distance?.qualifier == null }
            val secondsColumn = duration?.qualifier?.lowercase(Locale.ROOT)?.let { it in SECONDS } ?: true
            val problem = listOfNotNull(
                weight.takeIf { weightUnit == null },
                distance.takeIf { distanceUnit == null },
                duration.takeUnless { secondsColumn },
            ).firstOrNull()?.let { "the column \"${it.name}\" is not in a unit Titan reads" }

            return FitbodLayout(
                header = header,
                zone = context.zone,
                exerciseColumn = exercise,
                weightColumn = weight.name,
                durationColumn = duration?.name,
                distanceColumn = distance?.name,
                weightUnit = weightUnit,
                distanceUnit = distanceUnit,
                problem = problem,
            )
        }

        /**
         * The column for [name], as the app's export writes it (`Weight(kg)`) or as the support
         * extract does (`weight_kg`), or null when the header has neither.
         */
        private fun column(
            header: CsvHeader,
            name: String,
        ): QualifiedColumn? = header.qualified(name) ?: header.suffixed(name).firstOrNull()
    }
}

/** One pass over an export's rows, building the workouts as it goes. */
private class FitbodReading(private val layout: FitbodLayout) : RecordReading {

    private val names = SuffixedExerciseNames(FITBOD_CATALOGUE_NAMES, leadingEquipment = true)
    private val exercises = HashMap<String, ImportedExercise>()
    private val workouts = LinkedHashMap<Long, WorkoutDraft>()
    private var rows = 0

    override fun read(record: List<String>) {
        rows++
        val date = layout[record, DATE]
        val startedAt = SourceValues.localTimestamp(date, layout.zone)
            ?: SourceValues.localDateAtNoon(date, layout.zone)
            ?: return
        val exerciseName = layout[record, layout.exerciseColumn] ?: return

        val workout = workouts.getOrPut(startedAt) { WorkoutDraft(startedAt) }
        val block = workout.blocks.lastOrNull()?.takeIf { it.exercise.name == exerciseName }
            ?: ExerciseDraft(exercises.getOrPut(exerciseName) { names.exercise(exerciseName) })
                .also(workout.blocks::add)
        block.note(layout[record, NOTE])
        block.sets += set(record, block.isAssisted)
    }

    override fun result(): ParseResult =
        if (workouts.isEmpty() && rows > 0) {
            ParseResult.Malformed("none of the export's $rows rows has a date and an exercise that can be read")
        } else {
            ParseResult.Parsed(ImportedHistory(workouts = workouts.values.map(WorkoutDraft::toWorkout)))
        }

    /**
     * The set [record] describes, or one with nothing recorded when a figure in it is not a
     * number: the planner refuses that and counts it, where dropping the row here would lose it
     * without a word. An [assisted] exercise's weight is never recorded, so it cannot cost the
     * set either.
     */
    private fun set(
        record: List<String>,
        assisted: Boolean,
    ): ImportedSet {
        val type = if (layout[record, IS_WARMUP].equals(TRUE, ignoreCase = true)) SetType.WARMUP else SetType.NORMAL
        val weightText = layout[record, layout.weightColumn].takeUnless { assisted }
        val repsText = layout[record, REPS]
        val durationText = layout[record, layout.durationColumn]
        val distanceText = layout[record, layout.distanceColumn]
        val weight = SourceValues.decimal(weightText)
        val reps = SourceValues.decimal(repsText)
        val duration = SourceValues.elapsedSeconds(durationText)
        val distance = SourceValues.decimal(distanceText)
        val unreadable = unread(weightText, weight) || unread(repsText, reps) ||
            unread(durationText, duration) || unread(distanceText, distance)
        if (unreadable) return ImportedSet(setType = type)

        val performed = ImportedSet(
            setType = type,
            reps = reps?.roundToInt(),
            weight = weight?.let(layout::milligrams),
            duration = duration,
            distance = distance?.let { layout.distanceUnit?.toCentimetres(it) },
        )
        return if (!assisted && isHoldInReps(performed, layout[record, MULTIPLIER])) {
            performed.copy(reps = null, duration = performed.reps?.toLong())
        } else {
            performed
        }
    }

    /**
     * Whether [set] is a hold an older Fitbod wrote in reps: counted, with no time or distance,
     * under a [multiplier] of `0`, which Fitbod gives only to exercises it does not count by weight.
     */
    private fun isHoldInReps(
        set: ImportedSet,
        multiplier: String?,
    ): Boolean =
        SourceValues.decimal(multiplier) == 0.0 && (set.reps ?: 0) > 0 && (set.duration ?: 0L) == 0L &&
            (set.distance ?: 0L) == 0L

    /** Whether a cell held [text] that did not read as a [value]. */
    private fun unread(
        text: String?,
        value: Any?,
    ): Boolean = text != null && value == null
}

private class WorkoutDraft(val startedAt: Long) {

    val blocks = mutableListOf<ExerciseDraft>()

    fun toWorkout() = ImportedWorkout(startedAt = startedAt, exercises = blocks.map(ExerciseDraft::toLine))
}

private class ExerciseDraft(val exercise: ImportedExercise) {
    val sets = mutableListOf<ImportedSet>()

    /** Whether the exercise names assistance, whose weight is a counterweight rather than a load. */
    val isAssisted: Boolean = SuffixedExerciseNames.namesAssistance(exercise.name)
    private val notes = LinkedHashSet<String>()

    fun note(text: String?) {
        SourceValues.note(text)?.let(notes::add)
    }

    fun toLine() =
        ImportedWorkoutExercise(
            exercise = exercise,
            sets = sets.toList(),
            notes = notes.joinToString("\n").ifEmpty { null },
        )
}

private const val DATE = "Date"
private const val EXERCISE = "Exercise"
private const val EXERCISE_NAME = "exercise_name"
private const val REPS = "Reps"
private const val WEIGHT = "Weight"
private const val DURATION = "Duration"
private const val DISTANCE = "Distance"
private const val IS_WARMUP = "isWarmup"
private const val NOTE = "Note"
private const val MULTIPLIER = "multiplier"

/** What `isWarmup` says for a warm-up, in any case. */
private const val TRUE = "true"

/** The labels a duration column's header may give its unit, lowercased: Fitbod writes `s` and `seconds`. */
private val SECONDS = setOf("s", "sec", "secs", "second", "seconds")
