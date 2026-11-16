package com.titan.core.domain.importer.fitnotes

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceLengthUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.csv.MalformedCsvException
import com.titan.core.domain.importer.csv.QualifiedColumn
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.ExerciseType
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Reads the two spreadsheets the FitNotes app exports from Settings › Spreadsheet Export: the
 * workout log (`FitNotes_Export.csv`) and the body tracker (`FitNotes_BodyTracker_Export.csv`).
 * Each is a file of its own, and this parser reads whichever it is given.
 *
 * ### The workout export
 *
 * FitNotes publishes no specification beyond where the button is, so what follows is what its
 * exporter writes and real exports contain. One row per set, in the order the sets were logged,
 * oldest day first:
 *
 * `Date, Exercise, Category, Weight, Weight Unit, Reps, Distance, Distance Unit, Time, Comment`
 *
 * - **Older versions** wrote the unit in the weight's header instead of a column of its own:
 *   `Weight (kgs)` or `Weight (lbs)`, with no `Weight Unit`.
 * - **FitNotes for iOS**, a separate app, writes `Weight (kg)` and `Weight (lbs)` side by side
 *   and fills the one the set was logged in, calls the comment `Notes`, and adds `Kind`, the
 *   letters of what the exercise records (`w`eight, `r`eps, `d`istance, `t`ime).
 * - `Comment` is only there when the user chose to export comments.
 *
 * Columns are read by name, never by position. `Date` is the day, `yyyy-MM-dd`, and nothing
 * says the time; see [SourceValues.localDateAtNoon] for the start a workout is given instead.
 * `Weight Unit` is `kgs` or `lbs`, set per exercise; `Distance Unit` is `m`, `km`, `ft` or `mi`;
 * `Time` is a duration, `0:05:30`. FitNotes fills only the columns the exercise's type records,
 * so a strength set has no distance, a run has no reps, and a run timed but not measured has a
 * distance of `0.0`, which the planner reads as not recorded. A weight or distance with no unit
 * anywhere in the file is in whatever the user had chosen, which [ImportContext.unitSystem]
 * stands in for.
 *
 * The exporter quotes the comment, always, and nothing else: an exercise called "Curl, EZ Bar"
 * arrives as two fields and pushes the rest of the row one column right. Such a row is read with
 * its surplus fields put back into `Exercise` (see [CsvHeader.rejoined]); a category with a comma
 * in it is rarer than such a name, and is read as part of the name.
 *
 * ### What becomes what
 *
 * FitNotes keeps one workout a day, so the rows that share a `Date` are one workout, wherever in
 * the file they are. Inside it, the rows naming one exercise are one exercise of the workout, in
 * the order the exercises were first logged that day, because FitNotes shows a day's sets of an
 * exercise together however they were interleaved. `Comment` is the set's note.
 *
 * Exercise names go through [SuffixedExerciseNames] with [FITNOTES_CATALOGUE_NAMES], so
 * "Flat Barbell Bench Press" lands on the catalogue's bench press. `Category` is a muscle group
 * the user files the exercise under; where it is one Titan has ("Chest", "Abs", "Calves"), it
 * becomes the exercise's primary muscle, and "Back", "Legs" and "Cardio", which name no single
 * muscle, are left out. The type is left to the planner to infer from the sets, except for an
 * exercise that counts no reps and records a distance or is filed under "Cardio": that is
 * endurance, even when FitNotes recorded a time and no distance, which the planner alone would
 * read as a hold.
 *
 * ### The body tracker export
 *
 * `Date, Time, Measurement, Value, Unit, Comment` — one row per figure, `Time` the wall-clock time
 * it was recorded, read in [ImportContext.zone]. The rows recorded at the same moment are one
 * [BodyMeasurement]. FitNotes' own measurements are read by their names (`Bodyweight`,
 * `Body Fat`, `Neck`, `Shoulders`, `Chest`, `Waist`, `Hips`, and `Upper Arm`, `Forearm`, `Thigh`
 * and `Calf`, each `(Left)` and `(Right)`), in the unit on the row: `kgs` or `lbs`, `cm` or `in`,
 * `%`. Titan records one arm, forearm, thigh and calf, so a left and a right become the larger of
 * the two, as [BodyMeasurement.forearm] says. `Comment` is the measurement's note.
 *
 * ### What is deliberately not imported
 *
 * - **The time of day of a workout, and its duration.** The export does not hold them; the
 *   planner estimates a duration from the sets.
 * - **The weight of an assisted exercise.** A name such as "Pull Up (Assisted)" records the
 *   assistance, not a load; see [SuffixedExerciseNames.isAssisted].
 * - **A measurement the user created.** Titan has no field for it.
 * - **Routines, supersets, workout comments, personal records, goals.** Only the full backup, a
 *   database file, holds them, and this parser reads the spreadsheets; it does not recognise the
 *   backup. Records are derived from the sets anyway.
 *
 * ### What a damaged file costs
 *
 * A header without `Date`, `Exercise`, `Category`, `Reps` and a weight column, or without `Date`,
 * `Time`, `Measurement`, `Value` and `Unit`, is not a FitNotes export at all, and is
 * [ParseResult.NotRecognised]. Past that, one bad row is skipped rather than costing the history:
 * a row with no date that reads or no exercise name is left out, and a set whose weight, reps,
 * distance or time is not a number, or whose unit is not one, is passed on with nothing recorded,
 * so the planner refuses it and counts it in the report. A measurement row with a figure or a
 * unit that does not read is passed on as an empty entry, to be counted the same way. Only a file
 * whose every row is unreadable, or whose CSV breaks in a way no row can be recovered from, is
 * [ParseResult.Malformed].
 */
class FitNotesParser : HistoryParser {

    override fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult =
        source.open().use { stream ->
            val reader = CsvReader.open(stream)
            val reading = readHeader(reader)?.let { header ->
                WorkoutLayout.of(header, reader.delimiter, context)?.let(::WorkoutReading)
                    ?: MeasurementLayout.of(header, reader.delimiter, context)?.let(::MeasurementReading)
            }
            if (reading == null) {
                ParseResult.NotRecognised
            } else {
                try {
                    reader.records().forEach(reading::read)
                    reading.result()
                } catch (failure: MalformedCsvException) {
                    ParseResult.Malformed(failure.message.orEmpty())
                }
            }
        }

    /**
     * The first record as a header, or null when there is none — including when it is not CSV
     * at all, such as the backup's database file, which is a file that is not an export rather
     * than a broken one.
     */
    private fun readHeader(reader: CsvReader): CsvHeader? =
        try {
            reader.readRecord()?.let(::CsvHeader)
        } catch (_: MalformedCsvException) {
            null
        }
}

/** One pass over an export's rows. */
private interface FitNotesReading {

    fun read(record: List<String>)

    fun result(): ParseResult
}

/** Whether [text] is there and is not a number. */
private fun unreadable(text: String?): Boolean = text != null && SourceValues.decimal(text) == null

// ---------------------------------------------------------------------------------- workouts

/** The columns one workout export has, and how its figures' units are known. */
private class WorkoutLayout(
    private val header: CsvHeader,
    private val delimiter: Char,
    val context: ImportContext,
    private val weights: List<QualifiedColumn>,
    val notesColumn: String?,
) {
    operator fun get(
        record: List<String>,
        column: String?,
    ): String? = column?.let { header[record, it] }

    /** [record] with a name the exporter split at a comma put back together. */
    fun rejoined(record: List<String>): List<String> = header.rejoined(record, EXERCISE, delimiter)

    /** [record]'s weight, from the first weight column it filled, and that column. */
    fun weight(record: List<String>): Pair<String, QualifiedColumn>? =
        weights.firstNotNullOfOrNull { column -> header[record, column.name]?.let { it to column } }

    /**
     * The unit a weight in [column] on [record] is in: the header's, else the row's own, else the
     * user's when neither says. Null when what says it is not a unit.
     */
    fun weightUnit(
        record: List<String>,
        column: QualifiedColumn,
    ): SourceWeightUnit? {
        val label = column.qualifier ?: header[record, WEIGHT_UNIT] ?: return SourceWeightUnit.of(context.unitSystem)
        return SourceWeightUnit.parse(label)
    }

    /** The unit [record]'s distance is in: the row's own, else the user's. Null when it is not a unit. */
    fun distanceUnit(record: List<String>): SourceDistanceUnit? {
        val label = header[record, DISTANCE_UNIT] ?: return SourceDistanceUnit.of(context.unitSystem)
        return SourceDistanceUnit.parse(label)
    }

    companion object {

        /** The columns every FitNotes workout export has had, besides a weight column. */
        private val SIGNATURE = listOf(DATE, EXERCISE, CATEGORY, REPS)

        /** [header]'s layout, or null when it is not a FitNotes workout export's. */
        fun of(
            header: CsvHeader,
            delimiter: Char,
            context: ImportContext,
        ): WorkoutLayout? {
            val weights = header.qualifiedColumns(WEIGHT)
            if (!header.containsAll(SIGNATURE) || weights.isEmpty()) return null
            return WorkoutLayout(
                header,
                delimiter,
                context,
                weights,
                listOf(COMMENT, NOTES).firstOrNull(header::contains),
            )
        }
    }
}

/** One pass over a workout export's rows, building the workouts as it goes. */
private class WorkoutReading(private val layout: WorkoutLayout) : FitNotesReading {

    private val names = SuffixedExerciseNames(FITNOTES_CATALOGUE_NAMES)
    private val exercises = LinkedHashMap<String, ExerciseEvidence>()
    private val workouts = LinkedHashMap<Long, WorkoutDraft>()
    private var rows = 0

    override fun read(record: List<String>) {
        rows++
        val row = layout.rejoined(record)
        val startedAt = SourceValues.localDateAtNoon(layout[row, DATE], layout.context.zone) ?: return
        val exerciseName = layout[row, EXERCISE] ?: return

        exercises.getOrPut(exerciseName) { ExerciseEvidence() }.observe(layout, row)
        workouts.getOrPut(startedAt) { WorkoutDraft(startedAt) }
            .sets(exerciseName) += set(row, SuffixedExerciseNames.isAssisted(exerciseName))
    }

    override fun result(): ParseResult {
        if (workouts.isEmpty() && rows > 0) {
            return ParseResult.Malformed("none of the export's $rows rows has a date and an exercise that can be read")
        }
        val resolved = exercises.mapValues { (name, evidence) -> evidence.exercise(names.exercise(name)) }
        return ParseResult.Parsed(ImportedHistory(workouts = workouts.values.map { it.toWorkout(resolved) }))
    }

    /**
     * The set [row] describes, or one with nothing recorded when a figure in it is not a number or
     * a unit is not one: the planner refuses that and counts it, where dropping the row here would
     * lose it without a word.
     */
    private fun set(
        row: List<String>,
        assisted: Boolean,
    ): ImportedSet {
        val weight = layout.weight(row)
        val milligrams = weight?.let { (text, column) ->
            layout.weightUnit(row, column)?.let { unit -> SourceValues.decimal(text)?.let(unit::toMilligrams) }
        }
        val distance = layout[row, DISTANCE]
        val centimetres = distance?.let { text ->
            layout.distanceUnit(row)?.let { unit -> SourceValues.decimal(text)?.let(unit::toCentimetres) }
        }
        val reps = layout[row, REPS]
        val time = layout[row, TIME]
        val seconds = SourceValues.elapsedSeconds(time)
        val unreadable = (weight != null && milligrams == null) || (distance != null && centimetres == null) ||
            unreadable(reps) || (time != null && seconds == null)
        if (unreadable) return ImportedSet()

        return ImportedSet(
            reps = SourceValues.decimal(reps)?.roundToInt(),
            weight = milligrams?.takeUnless { assisted },
            duration = seconds,
            distance = centimetres,
            notes = SourceValues.note(layout[row, layout.notesColumn]),
        )
    }
}

/**
 * What the rows naming one exercise say about it, across the whole file: the category it is filed
 * under, and which of the columns its type fills.
 */
private class ExerciseEvidence {

    private var category: String? = null
    private var countsReps = false
    private var tracksDistance = false

    fun observe(
        layout: WorkoutLayout,
        row: List<String>,
    ) {
        category = category ?: layout[row, CATEGORY]
        countsReps = countsReps || layout[row, REPS] != null
        tracksDistance = tracksDistance || layout[row, DISTANCE] != null || layout[row, DISTANCE_UNIT] != null ||
            layout[row, KIND]?.lowercase(Locale.ROOT)?.contains(KIND_DISTANCE) == true
    }

    /** [named], with what the rows said about its muscle and its type. */
    fun exercise(named: ImportedExercise): ImportedExercise {
        val filed = category?.let(ExerciseMatcher::normalised)
        val endurance = !countsReps && (tracksDistance || filed == CARDIO)
        return named.copy(
            type = ExerciseType.ENDURANCE.takeIf { endurance },
            primaryMuscle = filed?.let(CATEGORY_MUSCLES::get),
        )
    }
}

private class WorkoutDraft(val startedAt: Long) {

    private val blocks = LinkedHashMap<String, MutableList<ImportedSet>>()

    /** The sets of [exerciseName] in this workout so far, which a new set is added to. */
    fun sets(exerciseName: String): MutableList<ImportedSet> = blocks.getOrPut(exerciseName, ::mutableListOf)

    fun toWorkout(exercises: Map<String, ImportedExercise>) =
        ImportedWorkout(
            startedAt = startedAt,
            exercises = blocks.map { (name, sets) -> ImportedWorkoutExercise(exercises.getValue(name), sets.toList()) },
        )
}

// ------------------------------------------------------------------------------ measurements

/** The columns one body tracker export has. */
private class MeasurementLayout(
    private val header: CsvHeader,
    private val delimiter: Char,
    val context: ImportContext,
) {
    operator fun get(
        record: List<String>,
        column: String,
    ): String? = header[record, column]

    /** [record] with a measurement name the exporter split at a comma put back together. */
    fun rejoined(record: List<String>): List<String> = header.rejoined(record, MEASUREMENT, delimiter)

    companion object {

        private val SIGNATURE = listOf(DATE, TIME, MEASUREMENT, VALUE, UNIT)

        /** [header]'s layout, or null when it is not a FitNotes body tracker export's. */
        fun of(
            header: CsvHeader,
            delimiter: Char,
            context: ImportContext,
        ): MeasurementLayout? =
            if (header.containsAll(SIGNATURE)) MeasurementLayout(header, delimiter, context) else null
    }
}

/** What one of FitNotes' own measurements records, as a field of [BodyMeasurement]. */
private enum class Site {
    WEIGHT,
    BODY_FAT,
    NECK,
    SHOULDERS,
    CHEST,
    WAIST,
    HIPS,
    ARM,
    FOREARM,
    THIGH,
    CALF,
}

/** One pass over a body tracker export's rows. */
private class MeasurementReading(private val layout: MeasurementLayout) : FitNotesReading {

    private val entries = LinkedHashMap<Long, MeasurementDraft>()
    private val unreadableEntries = mutableListOf<BodyMeasurement>()
    private var rows = 0
    private var datedRows = 0

    override fun read(record: List<String>) {
        rows++
        val row = layout.rejoined(record)
        val takenAt = takenAt(row) ?: return
        datedRows++
        // A measurement the user created: Titan has nowhere to put it.
        val site = layout[row, MEASUREMENT]?.let { SITES[ExerciseMatcher.normalised(it)] } ?: return
        val figure = figure(site, layout[row, VALUE], layout[row, UNIT])
        if (figure == null) {
            // The planner refuses an entry that records nothing, and counts it.
            unreadableEntries += BodyMeasurement(createdAt = takenAt, updatedAt = takenAt)
        } else {
            entries.getOrPut(takenAt) { MeasurementDraft(takenAt) }.apply {
                record(site, figure)
                note(layout[row, COMMENT])
            }
        }
    }

    override fun result(): ParseResult =
        if (datedRows == 0 && rows > 0) {
            ParseResult.Malformed("none of the export's $rows rows has a date and time that can be read")
        } else {
            ParseResult.Parsed(
                ImportedHistory(
                    bodyMeasurements = entries.values.map(MeasurementDraft::toMeasurement) + unreadableEntries,
                ),
            )
        }

    /** When [row] was recorded: its date and wall-clock time, read in the user's zone. */
    private fun takenAt(row: List<String>): Long? =
        layout[row, DATE]?.let { date ->
            SourceValues.localTimestamp("$date ${layout[row, TIME].orEmpty()}", layout.context.zone)
        }

    /**
     * [value] in [site]'s unit in Titan — milligrams, tenths of a percent, millimetres — read in
     * the unit [label] names, or in the user's when it names none. Null when the value is not a
     * number or the label not a unit [site] is measured in.
     */
    private fun figure(
        site: Site,
        value: String?,
        label: String?,
    ): Long? {
        val number = SourceValues.decimal(value) ?: return null
        val system = layout.context.unitSystem
        return when (site) {
            Site.WEIGHT -> (label?.let(SourceWeightUnit::parse) ?: SourceWeightUnit.of(system).takeIf { label == null })
                ?.toMilligrams(number)

            Site.BODY_FAT -> (number * BODY_FAT_SCALE).roundToLong().takeIf { label == null || label == PERCENT }

            else -> (label?.let(SourceLengthUnit::parse) ?: SourceLengthUnit.of(system).takeIf { label == null })
                ?.toMillimetres(number)
        }
    }
}

/** The figures recorded at one moment, which become one [BodyMeasurement]. */
private class MeasurementDraft(private val takenAt: Long) {

    private val figures = HashMap<Site, Long>()
    private val notes = LinkedHashSet<String>()

    /**
     * Keeps [figure] for [site]: the first weight or body fat recorded at this moment, and the
     * larger circumference, which is how a left and a right side become Titan's one.
     */
    fun record(
        site: Site,
        figure: Long,
    ) {
        when (site) {
            Site.WEIGHT, Site.BODY_FAT -> figures.putIfAbsent(site, figure)
            else -> figures.merge(site, figure, ::maxOf)
        }
    }

    fun note(text: String?) {
        SourceValues.note(text)?.let(notes::add)
    }

    fun toMeasurement(): BodyMeasurement {
        fun int(site: Site): Int? = figures[site]?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
        return BodyMeasurement(
            weight = figures[Site.WEIGHT],
            neck = int(Site.NECK),
            shoulders = int(Site.SHOULDERS),
            chest = int(Site.CHEST),
            waist = int(Site.WAIST),
            hips = int(Site.HIPS),
            arm = int(Site.ARM),
            forearm = int(Site.FOREARM),
            thigh = int(Site.THIGH),
            calf = int(Site.CALF),
            bodyFat = int(Site.BODY_FAT),
            notes = notes.joinToString("\n").ifEmpty { null },
            createdAt = takenAt,
            updatedAt = takenAt,
        )
    }
}

private const val DATE = "Date"
private const val EXERCISE = "Exercise"
private const val CATEGORY = "Category"
private const val WEIGHT = "Weight"
private const val WEIGHT_UNIT = "Weight Unit"
private const val REPS = "Reps"
private const val DISTANCE = "Distance"
private const val DISTANCE_UNIT = "Distance Unit"
private const val TIME = "Time"
private const val COMMENT = "Comment"
private const val NOTES = "Notes"
private const val KIND = "Kind"
private const val MEASUREMENT = "Measurement"
private const val VALUE = "Value"
private const val UNIT = "Unit"

/** The letter FitNotes for iOS writes in `Kind` for an exercise that records a distance. */
private const val KIND_DISTANCE = 'd'

/** FitNotes' cardio category, by [ExerciseMatcher.normalised] name. */
private const val CARDIO = "cardio"

/** The unit FitNotes writes beside a body fat figure. */
private const val PERCENT = "%"

/** FitNotes writes body fat in percent (`15.5`); Titan stores tenths of one. */
private const val BODY_FAT_SCALE = 10

/**
 * FitNotes categories that are one of Titan's muscle groups, by [ExerciseMatcher.normalised] name,
 * to the group's `muscle_group.name`.
 *
 * FitNotes starts every user with eight categories — Shoulders, Triceps, Biceps, Chest, Back,
 * Legs, Abs and Cardio — and lets them add their own, which are as often a single muscle
 * ("Calves", "Forearm", "Trapezius") as anything. Five of the eight are a muscle group here.
 * Back and Legs are several (lats or middle back, quadriceps or hamstrings), and Cardio is none,
 * so an exercise filed under them gets no primary muscle rather than a guess. The rest are the
 * names of Titan's groups and the spellings of them users file exercises under.
 */
private val CATEGORY_MUSCLES: Map<String, String> = mapOf(
    "abs" to "ABDOMINALS",
    "abdominals" to "ABDOMINALS",
    "abductors" to "ABDUCTORS",
    "adductors" to "ADDUCTORS",
    "biceps" to "BICEPS",
    "calves" to "CALVES",
    "calf" to "CALVES",
    "chest" to "CHEST",
    "forearms" to "FOREARMS",
    "forearm" to "FOREARMS",
    "glutes" to "GLUTES",
    "gluteus" to "GLUTES",
    "hamstrings" to "HAMSTRINGS",
    "lats" to "LATS",
    "latissimus" to "LATS",
    "lower back" to "LOWER_BACK",
    "middle back" to "MIDDLE_BACK",
    "neck" to "NECK",
    "quadriceps" to "QUADRICEPS",
    "quads" to "QUADRICEPS",
    "shoulders" to "SHOULDERS",
    "traps" to "TRAPS",
    "trapezius" to "TRAPS",
    "triceps" to "TRICEPS",
)

/**
 * FitNotes' own measurements, by [ExerciseMatcher.normalised] name — which drops the `%` some
 * versions put after "Body Fat" and the parentheses around a side — to what each records.
 */
private val SITES: Map<String, Site> = mapOf(
    "bodyweight" to Site.WEIGHT,
    "body weight" to Site.WEIGHT,
    "body fat" to Site.BODY_FAT,
    "neck" to Site.NECK,
    "shoulders" to Site.SHOULDERS,
    "chest" to Site.CHEST,
    "waist" to Site.WAIST,
    "hips" to Site.HIPS,
    "upper arm left" to Site.ARM,
    "upper arm right" to Site.ARM,
    "forearm left" to Site.FOREARM,
    "forearm right" to Site.FOREARM,
    "thigh left" to Site.THIGH,
    "thigh right" to Site.THIGH,
    "calf left" to Site.CALF,
    "calf right" to Site.CALF,
)
