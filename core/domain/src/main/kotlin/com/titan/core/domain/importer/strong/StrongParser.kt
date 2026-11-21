package com.titan.core.domain.importer.strong

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.csv.MalformedCsvException
import com.titan.core.domain.importer.csv.QualifiedColumn
import com.titan.core.domain.model.SetType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reads the workout history the Strong app exports: Settings › Export Strong Data on iOS, Export
 * Data on Android.
 *
 * ### The format
 *
 * Strong publishes no specification, so what follows is what real exports contain. One CSV row
 * per row of a workout's log — a set, a rest timer that ran after one, or an exercise's note —
 * with the workout's own fields repeated on every row. Columns are read by name, never by
 * position, because the layout has changed between versions and platforms:
 *
 * - **iOS**, comma-separated: `Date, Workout Name, Duration, Exercise Name, Set Order, Weight,
 *   Reps, Distance, Seconds, Notes, Workout Notes, RPE`. The duration is spelled out (`1h 5m`),
 *   and the weight and distance carry no unit.
 * - **Android 6.x**, semicolon-separated and every field quoted: `Workout #, Date, Workout Name,
 *   Duration (sec), Exercise Name, Set Order, Weight (kg), Reps, RPE, Distance (meters), Seconds,
 *   Notes, Workout Notes` — the units in the header, the duration in seconds.
 * - **A unit-column layout**, semicolon-separated: `Weight` beside `Weight Unit`, `Distance` beside
 *   `Distance Unit`, and the duration last as `Workout Duration`.
 * - **Early exports** named the weight and distance columns after their unit alone (`lbs`, `mi.`).
 *
 * Any other `Weight (unit)` or `Distance (unit)` header is read by its unit too, so an imperial
 * user's `Weight (lbs)` needs nothing of its own. A figure whose unit nothing in the file names
 * is in whatever the user had chosen in Strong, which [ImportContext.unitSystem] stands in for.
 * `Date` is the workout's start, in local wall-clock time, and is read in [ImportContext.zone].
 *
 * `Set Order` says what a row is: a number for a working set, `W`, `D` or `F` for a warm-up, drop
 * or failure set, `Rest Timer` for the rest that followed the set before it (its length in
 * `Seconds`), and `Note` for the exercise's note (in `Notes`). A set's `Seconds` is how long it
 * lasted. Strong writes `0` in every column a set does not use, and the planner already reads a
 * zero distance or duration as not recorded, so the zeros pass through as written.
 *
 * ### What becomes what
 *
 * A workout is the rows that share a `Workout #`, a `Date` and a `Workout Name`. Inside it, a run
 * of consecutive rows naming one exercise is one exercise of the workout, so an exercise
 * performed twice with something between is two, in the order they happened. A rest timer
 * becomes the rest after the set it follows. Notes — whether on a `Note` row or, as iOS writes
 * them, on an exercise's first set — are the exercise's notes for that workout, because that is
 * what Strong's note is attached to.
 *
 * Exercise names go through [SuffixedExerciseNames] with [STRONG_CATALOGUE_NAMES], so
 * "Bench Press (Barbell)" lands on the catalogue's bench press and its parenthesis says which
 * equipment it was.
 *
 * ### What is deliberately not imported
 *
 * - **The weight of an `(Assisted)` exercise.** Strong records the counterweight a machine took
 *   off, as a positive number, and Titan has no column for assistance. Imported as a load it
 *   would make the easiest set the heaviest one; the reps are still the set. This is the same
 *   reading [com.titan.core.domain.importer.ImportPlanner] gives a negative weight.
 * - **Strong's records, templates and measurements.** The workout export holds none of them;
 *   records are derived from the sets anyway.
 * - **Supersets.** The export does not mark which exercises were one.
 *
 * ### What a damaged file costs
 *
 * A header without `Date`, `Workout Name`, `Exercise Name` and `Set Order` is not a Strong export
 * at all, and is [ParseResult.NotRecognised]. Past that, one bad row is skipped rather than
 * costing the history: a row with no date that reads or no exercise name is left out, and a set
 * whose weight, reps, distance or duration is not a number is passed on with nothing recorded,
 * so the planner refuses it and counts it in the report. An unreadable RPE alone is not recorded
 * and the set kept. Only a file whose every row is unreadable, or whose CSV breaks in a way no
 * row can be recovered from, is [ParseResult.Malformed].
 */
class StrongParser : HistoryParser {

    override fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult =
        source.open().use { stream ->
            val reader = CsvReader.open(stream)
            val header = readHeader(reader)
            val layout = header?.let { StrongLayout.of(it, context) }
            if (layout == null) {
                ParseResult.NotRecognised
            } else {
                try {
                    StrongReading(layout).apply { reader.records().forEach(::read) }.result()
                } catch (failure: MalformedCsvException) {
                    ParseResult.Malformed(failure.message.orEmpty())
                }
            }
        }

    /**
     * The first record as a header, or null when there is none — including when it is not CSV
     * at all, which is a file that is not an export rather than a broken one.
     */
    private fun readHeader(reader: CsvReader): CsvHeader? =
        try {
            reader.readRecord()?.let(::CsvHeader)
        } catch (_: MalformedCsvException) {
            null
        }
}

/** The columns one Strong export has, and how its figures' units are known. */
private class StrongLayout(
    private val header: CsvHeader,
    private val context: ImportContext,
    val weightColumn: String?,
    private val weightUnit: SourceWeightUnit?,
    val distanceColumn: String?,
    private val distanceUnit: SourceDistanceUnit?,
    val durationColumn: String?,
) {
    operator fun get(
        record: List<String>,
        column: String?,
    ): String? = column?.let { header[record, it] }

    /** The unit [record]'s weight is in: the header's, else the row's own, else the user's. */
    fun weightUnitOf(record: List<String>): SourceWeightUnit =
        weightUnit
            ?: header[record, WEIGHT_UNIT]?.let(SourceWeightUnit::parse)
            ?: SourceWeightUnit.of(context.unitSystem)

    /** The unit [record]'s distance is in: the header's, else the row's own, else the user's. */
    fun distanceUnitOf(record: List<String>): SourceDistanceUnit =
        distanceUnit
            ?: header[record, DISTANCE_UNIT]?.let(SourceDistanceUnit::parse)
            ?: SourceDistanceUnit.of(context.unitSystem)

    val zone get() = context.zone

    companion object {

        /** The columns every Strong export has had, and nothing else has all of. */
        private val SIGNATURE = listOf(DATE, WORKOUT_NAME, EXERCISE_NAME, SET_ORDER)

        /** [header]'s layout, or null when it is not a Strong export's. */
        fun of(
            header: CsvHeader,
            context: ImportContext,
        ): StrongLayout? {
            if (!header.containsAll(SIGNATURE)) return null
            // Early exports named the column after the unit alone: `lbs`, `kg`, `mi.`, `km`. A
            // column found by name keeps its own qualifier, or none: a unit-named column beside
            // it says nothing about its figures.
            val weight = header.qualified(WEIGHT)
                ?: header.columns.firstOrNull { SourceWeightUnit.parse(it) != null }?.let { QualifiedColumn(it, it) }
            val distance = header.qualified(DISTANCE)
                ?: header.columns.firstOrNull { SourceDistanceUnit.parse(it) != null }?.let { QualifiedColumn(it, it) }
            return StrongLayout(
                header = header,
                context = context,
                weightColumn = weight?.name,
                weightUnit = weight?.qualifier?.let(SourceWeightUnit::parse),
                distanceColumn = distance?.name,
                distanceUnit = distance?.qualifier?.let(SourceDistanceUnit::parse),
                durationColumn = (header.qualified(DURATION) ?: header.qualified(WORKOUT_DURATION))?.name,
            )
        }
    }
}

/** One pass over an export's rows, building the workouts as it goes. */
private class StrongReading(private val layout: StrongLayout) {

    private val names = SuffixedExerciseNames(STRONG_CATALOGUE_NAMES)
    private val exercises = HashMap<String, ImportedExercise>()
    private val workouts = LinkedHashMap<WorkoutKey, WorkoutDraft>()
    private var rows = 0

    fun read(record: List<String>) {
        rows++
        val date = layout[record, DATE] ?: return
        val exerciseName = layout[record, EXERCISE_NAME] ?: return
        val key = WorkoutKey(layout[record, WORKOUT_NUMBER], date, layout[record, WORKOUT_NAME])
        val workout = workouts[key] ?: startWorkout(key) ?: return

        workout.durationSeconds = workout.durationSeconds ?: SourceValues.elapsedSeconds(
            layout[record, layout.durationColumn],
        )
        workout.notes = workout.notes ?: layout[record, WORKOUT_NOTES]?.let(::noteText)

        val block = workout.blocks.lastOrNull()?.takeIf { it.exercise.name == exerciseName }
            ?: ExerciseDraft(exercises.getOrPut(exerciseName) { names.exercise(exerciseName) })
                .also(workout.blocks::add)
        val marker = layout[record, SET_ORDER]?.lowercase(Locale.ROOT)
        when (marker) {
            REST_TIMER -> SourceValues.elapsedSeconds(layout[record, SECONDS])?.let(block::restAfterLastSet)

            NOTE -> block.note(layout[record, NOTES])

            else -> {
                block.note(layout[record, NOTES])
                block.sets += set(record, marker?.let(SET_TYPES::get) ?: SetType.NORMAL, block.isAssisted)
            }
        }
    }

    fun result(): ParseResult =
        if (workouts.isEmpty() && rows > 0) {
            ParseResult.Malformed("none of the export's $rows rows has a date and an exercise that can be read")
        } else {
            ParseResult.Parsed(ImportedHistory(workouts = workouts.values.map(WorkoutDraft::toWorkout)))
        }

    /** A new workout for [key], or null when its date does not read — every row of it is lost. */
    private fun startWorkout(key: WorkoutKey): WorkoutDraft? {
        val startedAt = SourceValues.localTimestamp(key.date, layout.zone) ?: return null
        return WorkoutDraft(startedAt, key.name).also { workouts[key] = it }
    }

    /**
     * The set [record] describes, or one with nothing recorded when a figure in it is not a
     * number: the planner refuses that and counts it, where dropping the row here would lose it
     * without a word. An [assisted] exercise's weight is never recorded, so it cannot cost the
     * set either.
     */
    private fun set(
        record: List<String>,
        type: SetType,
        assisted: Boolean,
    ): ImportedSet {
        val weightText = layout[record, layout.weightColumn].takeUnless { assisted }
        val repsText = layout[record, REPS]
        val distanceText = layout[record, layout.distanceColumn]
        val secondsText = layout[record, SECONDS]
        val weight = SourceValues.decimal(weightText)
        val reps = SourceValues.decimal(repsText)
        val distance = SourceValues.decimal(distanceText)
        val seconds = SourceValues.elapsedSeconds(secondsText)
        val unreadable = unread(weightText, weight) || unread(repsText, reps) ||
            unread(distanceText, distance) || unread(secondsText, seconds)
        if (unreadable) return ImportedSet(setType = type)

        return ImportedSet(
            setType = type,
            reps = reps?.roundToInt(),
            weight = weight?.let(layout.weightUnitOf(record)::toMilligrams),
            duration = seconds,
            distance = distance?.let(layout.distanceUnitOf(record)::toCentimetres),
            rpe = SourceValues.decimal(layout[record, RPE])?.let { (it * RPE_SCALE).roundToInt() },
        )
    }

    /** Whether a cell held [text] that did not read as a [value]. */
    private fun unread(
        text: String?,
        value: Any?,
    ): Boolean = text != null && value == null
}

/** What tells one workout's rows from another's. */
private data class WorkoutKey(
    val number: String?,
    val date: String,
    val name: String?,
)

private class WorkoutDraft(
    val startedAt: Long,
    val name: String?,
) {
    var durationSeconds: Long? = null
    var notes: String? = null
    val blocks = mutableListOf<ExerciseDraft>()

    fun toWorkout() =
        ImportedWorkout(
            startedAt = startedAt,
            durationSeconds = durationSeconds,
            name = name,
            notes = notes,
            exercises = blocks.map(ExerciseDraft::toLine),
        )
}

private class ExerciseDraft(val exercise: ImportedExercise) {
    val sets = mutableListOf<ImportedSet>()

    /** Whether the exercise is an `(Assisted)` one, whose weight is a counterweight. */
    val isAssisted: Boolean = SuffixedExerciseNames.split(exercise.name).second.equals(ASSISTED, ignoreCase = true)
    private val notes = LinkedHashSet<String>()

    fun note(text: String?) {
        text?.let(::noteText)?.trim()?.takeIf(String::isNotEmpty)?.let(notes::add)
    }

    /** A rest timer row: the rest after the set before it, if there is one to attach it to. */
    fun restAfterLastSet(seconds: Long) {
        if (sets.isNotEmpty()) sets[sets.lastIndex] = sets.last().copy(restAfter = seconds)
    }

    fun toLine() =
        ImportedWorkoutExercise(
            exercise = exercise,
            sets = sets.toList(),
            notes = notes.joinToString("\n").ifEmpty { null },
        )
}

/**
 * [text] with every line break as `\n`: the ones iOS exports escape restored — it writes a note's
 * line break as the two characters `\n` rather than quoting a real one — and a quoted CRLF or CR
 * made the line break the rest of the app writes.
 */
private fun noteText(text: String): String = text.replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n')

private const val DATE = "Date"
private const val WORKOUT_NUMBER = "Workout #"
private const val WORKOUT_NAME = "Workout Name"
private const val DURATION = "Duration"
private const val WORKOUT_DURATION = "Workout Duration"
private const val EXERCISE_NAME = "Exercise Name"
private const val SET_ORDER = "Set Order"
private const val WEIGHT = "Weight"
private const val WEIGHT_UNIT = "Weight Unit"
private const val REPS = "Reps"
private const val RPE = "RPE"
private const val DISTANCE = "Distance"
private const val DISTANCE_UNIT = "Distance Unit"
private const val SECONDS = "Seconds"
private const val NOTES = "Notes"
private const val WORKOUT_NOTES = "Workout Notes"

/** `Set Order` values that are not a set, lowercased. */
private const val REST_TIMER = "rest timer"
private const val NOTE = "note"

/** `Set Order` values that mark a set's type, lowercased; a number is a working set. */
private val SET_TYPES = mapOf(
    "w" to SetType.WARMUP,
    "d" to SetType.DROP_SET,
    "f" to SetType.FAILURE,
)

private const val ASSISTED = "Assisted"

/** Strong writes RPE as written (`8.5`); Titan stores it times ten. */
private const val RPE_SCALE = 10
