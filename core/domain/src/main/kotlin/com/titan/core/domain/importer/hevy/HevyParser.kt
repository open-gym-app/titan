package com.titan.core.domain.importer.hevy

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
import com.titan.core.domain.importer.RecordReading
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceLengthUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.readAll
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.SetType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reads the two files the Hevy app exports: the workouts (Settings › Export & Import Data ›
 * Export Workouts, `workout_data.csv`; the website's is `workouts.csv`) and the body
 * measurements (Export Measurements, `measurement_data.csv`). Each is a file of its own, and
 * this parser reads whichever it is given.
 *
 * ### The workout export
 *
 * Hevy publishes no specification, so what follows is what real exports contain. One CSV row
 * per set, every string quoted, with the workout's and the exercise's fields repeated on each:
 *
 * `title, start_time, end_time, description, exercise_title, superset_id, exercise_notes,
 * set_index, set_type, weight_kg, reps, distance_km, duration_seconds, rpe`
 *
 * The weight and distance columns are named after the units the account used when it exported
 * — `weight_lbs` and `distance_miles` for an imperial one, and either can change without the
 * other — so they are found by their unit rather than by name, and any `weight_<unit>` or
 * `distance_<unit>` column is read in its unit. Columns are read by name, never by position.
 *
 * - `start_time` and `end_time` are wall-clock time, to the minute, written in the language the
 *   app or the website was in; [HevyTimestamps] reads them, in [ImportContext.zone]. The
 *   difference between them is the workout's duration.
 * - A blank cell is zero or not tracked, which Hevy does not tell apart and neither does the
 *   planner.
 * - `set_type` is `normal`, `warmup`, `dropset` or `failure`; `rpe` is a number such as `8.5`.
 * - `superset_id` is a number shared by the exercises of one superset, within one workout.
 * - The app writes a line break in a note as the two characters `\n`; the website quotes a real
 *   one. Both become a line break.
 * - The app writes the newest workout first; the order does not matter to the import.
 *
 * ### What becomes what
 *
 * A workout is a run of consecutive rows sharing a `title`, a `start_time` and an `end_time`;
 * two runs with the same three are two workouts, and the planner keeps the first of any two
 * that start in the same minute. Inside one, an exercise is a run of rows naming the same
 * exercise in the same superset whose `set_index` keeps counting up: Hevy restarts it at 0 for
 * every exercise, so the same exercise done twice in a row is still two. `description` is the
 * workout's notes and `exercise_notes`, repeated on every row, the exercise's.
 *
 * Exercise names go through [SuffixedExerciseNames] with [HEVY_CATALOGUE_NAMES], so
 * "Bench Press (Barbell)" lands on the catalogue's bench press and its parenthesis says which
 * equipment it was.
 *
 * ### The measurement export
 *
 * `date, weight_kg, fat_percent, neck_in, shoulder_in, chest_in, left_bicep_in, right_bicep_in,
 * left_forearm_in, right_forearm_in, abdomen_in, waist_in, hips_in, left_thigh_in,
 * right_thigh_in, left_calf_in, right_calf_in` — one row per entry, the weight's unit and each
 * circumference's in its column's name, read as the workouts' are. Each row is a
 * [BodyMeasurement] taken at `date`. Titan records one arm, forearm, thigh and calf, so a left
 * and a right become the larger of the two, as [BodyMeasurement.forearm] says.
 *
 * ### What is deliberately not imported
 *
 * - **The weight of an assisted exercise.** "Pull Up (Assisted)" and Hevy's band-assisted
 *   "Pull Up (Band)" record the assistance, not a load; see [SuffixedExerciseNames.isAssisted].
 * - **The abdomen.** Titan has no site for it, and the waist Hevy records beside it is a
 *   different measurement, so it is not stood in for.
 * - **Routines, records, progress photos.** Neither export holds them; records are derived from
 *   the sets anyway.
 * - **Translated exercise names' catalogue matches.** The app writes its library's names in the
 *   app's language, and [HEVY_CATALOGUE_NAMES] holds the English ones; see there.
 *
 * ### What a damaged file costs
 *
 * A header without `title`, `start_time`, `exercise_title` and `set_index`, or without `date`,
 * `fat_percent` and a weight column, is not a Hevy export at all, and is
 * [ParseResult.NotRecognised]. Past that, one bad row is skipped rather than costing the
 * history: a row with no date that reads or no exercise name is left out, and a set whose
 * weight, reps, distance or duration is not a number is passed on with nothing recorded, so the
 * planner refuses it and counts it in the report. An unreadable RPE alone is not recorded and
 * the set kept. A measurement with a figure that is not a number is likewise passed on empty,
 * to be counted. Only a file whose every row is unreadable, or whose CSV breaks in a way no row
 * can be recovered from, is [ParseResult.Malformed].
 */
class HevyParser : HistoryParser {

    override fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult =
        source.open().use { stream ->
            val reader = CsvReader.open(stream)
            val reading = reader.readHeader()?.let { header ->
                WorkoutLayout.of(header)?.let { WorkoutReading(it, context) }
                    ?: MeasurementLayout.of(header)?.let { MeasurementReading(it, context) }
            }
            reading?.readAll(reader, context) ?: ParseResult.NotRecognised
        }
}

/** A column whose name says the unit of the figures in it, and that unit. */
private data class UnitColumn<U>(
    val name: String,
    val unit: U,
)

/**
 * The columns of [header] called [name] with a unit [parse] reads after the underscore, in
 * header order.
 */
private fun <U : Any> unitColumns(
    header: CsvHeader,
    name: String,
    parse: (String) -> U?,
): List<UnitColumn<U>> =
    header.suffixed(name).mapNotNull { column ->
        column.qualifier?.let(parse)?.let { UnitColumn(column.name, it) }
    }

/**
 * [record]'s figure from the first of [columns] that has one, with that column's unit — so a
 * file carrying `weight_kg` beside `weight_lbs` reads whichever the row filled.
 */
private fun <U> CsvHeader.firstFigure(
    record: List<String>,
    columns: List<UnitColumn<U>>,
): Pair<String, U>? = columns.firstNotNullOfOrNull { column -> this[record, column.name]?.let { it to column.unit } }

/** A weight figure and its unit, in milligrams; null when the figure is not a number. */
private fun milligrams(figure: Pair<String, SourceWeightUnit>): Long? =
    SourceValues.decimal(figure.first)?.let(figure.second::toMilligrams)

/** A distance figure and its unit, in centimetres; null when the figure is not a number. */
private fun centimetres(figure: Pair<String, SourceDistanceUnit>): Long? =
    SourceValues.decimal(figure.first)?.let(figure.second::toCentimetres)

// ---------------------------------------------------------------------------------- workouts

/** The columns one workout export has, and the units its figures are in. */
private class WorkoutLayout(
    private val header: CsvHeader,
    private val weights: List<UnitColumn<SourceWeightUnit>>,
    private val distances: List<UnitColumn<SourceDistanceUnit>>,
) {
    operator fun get(
        record: List<String>,
        column: String,
    ): String? = header[record, column]

    fun weight(record: List<String>): Pair<String, SourceWeightUnit>? = header.firstFigure(record, weights)

    fun distance(record: List<String>): Pair<String, SourceDistanceUnit>? = header.firstFigure(record, distances)

    companion object {

        /** The columns every Hevy workout export has had, and nothing else has all of. */
        private val SIGNATURE = listOf(TITLE, START_TIME, EXERCISE_TITLE, SET_INDEX)

        /** [header]'s layout, or null when it is not a Hevy workout export's. */
        fun of(header: CsvHeader): WorkoutLayout? =
            if (header.containsAll(SIGNATURE)) {
                WorkoutLayout(
                    header,
                    unitColumns(header, WEIGHT, SourceWeightUnit::parse),
                    unitColumns(header, DISTANCE, SourceDistanceUnit::parse),
                )
            } else {
                null
            }
    }
}

/** One pass over a workout export's rows, building the workouts as it goes. */
private class WorkoutReading(
    private val layout: WorkoutLayout,
    private val context: ImportContext,
) : RecordReading {

    private val names = SuffixedExerciseNames(HEVY_CATALOGUE_NAMES)
    private val exercises = HashMap<String, ImportedExercise>()
    private val workouts = mutableListOf<WorkoutDraft>()
    private var currentKey: WorkoutKey? = null
    private var current: WorkoutDraft? = null
    private var rows = 0

    override var setCount = 0
        private set

    override fun read(record: List<String>) {
        rows++
        val start = layout[record, START_TIME] ?: return
        val exerciseName = layout[record, EXERCISE_TITLE] ?: return
        val key = WorkoutKey(layout[record, TITLE], start, layout[record, END_TIME])
        if (key != currentKey) {
            currentKey = key
            current = startWorkout(key, record)?.also(workouts::add)
        }
        val workout = current ?: return

        // Read as a number, not an integer, for the reason supersetGroup gives: a re-saved `1.0`.
        val setIndex = SourceValues.decimal(layout[record, SET_INDEX])?.toLong()
        val superset = layout[record, SUPERSET_ID]
        val block = workout.blocks.lastOrNull()?.takeIf { it.continues(exerciseName, superset, setIndex) }
            ?: ExerciseDraft(
                name = exerciseName,
                exercise = exercises.getOrPut(exerciseName) { names.exercise(exerciseName) },
                superset = superset,
                supersetGroup = workout.supersetGroup(superset),
            ).also(workout.blocks::add)
        block.lastSetIndex = setIndex ?: block.lastSetIndex
        block.note(layout[record, EXERCISE_NOTES])
        block.sets += set(record, block.isAssisted)
        setCount++
    }

    override fun result(): ParseResult =
        if (workouts.isEmpty() && rows > 0) {
            ParseResult.Malformed("none of the export's $rows rows has a start time and an exercise that can be read")
        } else {
            ParseResult.Parsed(ImportedHistory(workouts = workouts.map(WorkoutDraft::toWorkout)))
        }

    /** A new workout for [key], or null when its start does not read — every row of it is lost. */
    private fun startWorkout(
        key: WorkoutKey,
        record: List<String>,
    ): WorkoutDraft? {
        val startedAt = HevyTimestamps.parse(key.start, context.zone) ?: return null
        val finishedAt = HevyTimestamps.parse(key.end, context.zone)
        return WorkoutDraft(
            startedAt = startedAt,
            durationSeconds = finishedAt?.takeIf { it > startedAt }?.let { (it - startedAt) / MILLIS_PER_SECOND },
            name = key.title,
            notes = SourceValues.note(layout[record, DESCRIPTION]),
        )
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
        val type = layout[record, SET_TYPE]?.lowercase(Locale.ROOT)?.let(SET_TYPES::get) ?: SetType.NORMAL
        val weightFigure = layout.weight(record).takeUnless { assisted }
        val repsText = layout[record, REPS]
        val distanceFigure = layout.distance(record)
        val secondsText = layout[record, DURATION_SECONDS]
        val weight = weightFigure?.let(::milligrams)
        val reps = SourceValues.decimal(repsText)
        val distance = distanceFigure?.let(::centimetres)
        val seconds = SourceValues.elapsedSeconds(secondsText)
        val unreadable = SourceValues.isUnread(weightFigure, weight) || SourceValues.isUnread(repsText, reps) ||
            SourceValues.isUnread(distanceFigure, distance) || SourceValues.isUnread(secondsText, seconds)
        if (unreadable) return ImportedSet(setType = type)

        return ImportedSet(
            setType = type,
            reps = reps?.roundToInt(),
            weight = weight,
            duration = seconds,
            distance = distance,
            rpe = SourceValues.decimal(layout[record, RPE])?.let { (it * RPE_SCALE).roundToInt() },
        )
    }
}

/** What tells one workout's rows from the next one's. */
private data class WorkoutKey(
    val title: String?,
    val start: String,
    val end: String?,
)

private class WorkoutDraft(
    val startedAt: Long,
    val durationSeconds: Long?,
    val name: String?,
    val notes: String?,
) {
    val blocks = mutableListOf<ExerciseDraft>()
    private val supersets = HashMap<String, Int>()

    /**
     * The superset group [id] is within this workout, or null when it is in none. Only equality
     * matters to the planner, so each distinct id gets the next number, and `1` and `1.0` — as a
     * spreadsheet that re-saved the file writes it — are one superset.
     */
    fun supersetGroup(id: String?): Int? =
        id?.let { text -> supersets.getOrPut(SourceValues.decimal(text)?.toString() ?: text) { supersets.size } }

    fun toWorkout() =
        ImportedWorkout(
            startedAt = startedAt,
            durationSeconds = durationSeconds,
            name = name,
            notes = notes,
            exercises = blocks.map(ExerciseDraft::toLine),
        )
}

private class ExerciseDraft(
    val name: String,
    private val exercise: ImportedExercise,
    private val superset: String?,
    private val supersetGroup: Int?,
) {
    val sets = mutableListOf<ImportedSet>()
    var lastSetIndex: Long? = null
    private val notes = LinkedHashSet<String>()

    /**
     * Whether the exercise's weight is the assistance rather than a load — see
     * [SuffixedExerciseNames.isAssisted] and [BAND_ASSISTED] — worked out once, not per set.
     */
    val isAssisted: Boolean =
        SuffixedExerciseNames.isAssisted(name) || ExerciseMatcher.normalised(name) in BAND_ASSISTED

    /**
     * Whether a row naming [exerciseName] in [superset] at [setIndex] is the next set of this
     * exercise rather than the first of another: the same exercise and superset, and a set
     * index that has not started again. A missing index cannot tell, so it continues.
     */
    fun continues(
        exerciseName: String,
        superset: String?,
        setIndex: Long?,
    ): Boolean {
        val previous = lastSetIndex
        return exerciseName == name && superset == this.superset &&
            (setIndex == null || previous == null || setIndex > previous)
    }

    fun note(text: String?) {
        SourceValues.note(text)?.let(notes::add)
    }

    fun toLine() =
        ImportedWorkoutExercise(
            exercise = exercise,
            sets = sets.toList(),
            notes = notes.joinToString("\n").ifEmpty { null },
            supersetGroup = supersetGroup,
        )
}

// ------------------------------------------------------------------------------ measurements

/** The columns one measurement export has, and the units its figures are in. */
private class MeasurementLayout(
    private val header: CsvHeader,
    private val weights: List<UnitColumn<SourceWeightUnit>>,
    private val sites: Map<Site, List<List<UnitColumn<SourceLengthUnit>>>>,
) {
    operator fun get(
        record: List<String>,
        column: String,
    ): String? = header[record, column]

    fun weight(record: List<String>): Pair<String, SourceWeightUnit>? = header.firstFigure(record, weights)

    /** [record]'s figures for [site]: one per side it is measured on, each with its unit. */
    fun site(
        record: List<String>,
        site: Site,
    ): List<Pair<String, SourceLengthUnit>> = sites[site].orEmpty().mapNotNull { header.firstFigure(record, it) }

    companion object {

        /** [header]'s layout, or null when it is not a Hevy measurement export's. */
        fun of(header: CsvHeader): MeasurementLayout? {
            val weights = unitColumns(header, WEIGHT, SourceWeightUnit::parse)
            if (!header.containsAll(listOf(DATE, FAT_PERCENT)) || weights.isEmpty()) return null
            val sites = Site.entries.associateWith { site ->
                site.columns.map { column -> unitColumns(header, column, SourceLengthUnit::parse) }
            }
            return MeasurementLayout(header, weights, sites)
        }
    }
}

/**
 * A body site Titan records, and the Hevy columns that measure it: one, or a left and a right.
 * Hevy's `abdomen` is none of them; see the class doc.
 */
private enum class Site(vararg val columns: String) {
    NECK("neck"),
    SHOULDERS("shoulder"),
    CHEST("chest"),
    WAIST("waist"),
    HIPS("hips"),
    ARM("left_bicep", "right_bicep"),
    FOREARM("left_forearm", "right_forearm"),
    THIGH("left_thigh", "right_thigh"),
    CALF("left_calf", "right_calf"),
}

/** One pass over a measurement export's rows. */
private class MeasurementReading(
    private val layout: MeasurementLayout,
    private val context: ImportContext,
) : RecordReading {

    private val measurements = mutableListOf<BodyMeasurement>()
    private var rows = 0
    private var datedRows = 0

    override val setCount = 0

    override fun read(record: List<String>) {
        rows++
        val takenAt = HevyTimestamps.parse(layout[record, DATE], context.zone) ?: return
        datedRows++
        val weight = layout.weight(record)
        val bodyFat = layout[record, FAT_PERCENT]
        val sites = Site.entries.associateWith { layout.site(record, it) }
        val figures = listOfNotNull(weight?.first, bodyFat) + sites.values.flatten().map { it.first }
        when {
            // A row with nothing in it: an entry Hevy kept for a photo, which the export leaves out.
            figures.isEmpty() -> return

            // The planner refuses an entry that records nothing, and counts it.
            figures.any(SourceValues::isUnreadableDecimal) ->
                measurements += BodyMeasurement(createdAt = takenAt, updatedAt = takenAt)

            else -> measurements += measurement(takenAt, weight, bodyFat, sites)
        }
    }

    override fun result(): ParseResult =
        if (datedRows == 0 && rows > 0) {
            ParseResult.Malformed("none of the export's $rows rows has a date that can be read")
        } else {
            ParseResult.Parsed(ImportedHistory(bodyMeasurements = measurements.toList()))
        }

    private fun measurement(
        takenAt: Long,
        weight: Pair<String, SourceWeightUnit>?,
        bodyFat: String?,
        sites: Map<Site, List<Pair<String, SourceLengthUnit>>>,
    ): BodyMeasurement {
        fun mm(site: Site): Int? =
            sites.getValue(site)
                .mapNotNull { (text, unit) -> SourceValues.decimal(text)?.let(unit::toMillimetres) }
                .maxOrNull()
                ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                ?.toInt()
        return BodyMeasurement(
            weight = weight?.let(::milligrams),
            neck = mm(Site.NECK),
            shoulders = mm(Site.SHOULDERS),
            chest = mm(Site.CHEST),
            waist = mm(Site.WAIST),
            hips = mm(Site.HIPS),
            arm = mm(Site.ARM),
            forearm = mm(Site.FOREARM),
            thigh = mm(Site.THIGH),
            calf = mm(Site.CALF),
            bodyFat = SourceValues.decimal(bodyFat)?.let { (it * BODY_FAT_SCALE).roundToInt() },
            createdAt = takenAt,
            updatedAt = takenAt,
        )
    }
}

private const val TITLE = "title"
private const val START_TIME = "start_time"
private const val END_TIME = "end_time"
private const val DESCRIPTION = "description"
private const val EXERCISE_TITLE = "exercise_title"
private const val SUPERSET_ID = "superset_id"
private const val EXERCISE_NOTES = "exercise_notes"
private const val SET_INDEX = "set_index"
private const val SET_TYPE = "set_type"
private const val WEIGHT = "weight"
private const val REPS = "reps"
private const val DISTANCE = "distance"
private const val DURATION_SECONDS = "duration_seconds"
private const val RPE = "rpe"
private const val DATE = "date"
private const val FAT_PERCENT = "fat_percent"

/** `set_type` values, lowercased. */
private val SET_TYPES = mapOf(
    "normal" to SetType.NORMAL,
    "warmup" to SetType.WARMUP,
    "dropset" to SetType.DROP_SET,
    "failure" to SetType.FAILURE,
)

/**
 * Hevy's assisted exercises whose names do not say "Assisted", by [ExerciseMatcher.normalised]
 * name: its library logs a band pull-up's band as assistance, like the machine's counterweight.
 */
private val BAND_ASSISTED = setOf(ExerciseMatcher.normalised("Pull Up (Band)"))

private const val MILLIS_PER_SECOND = 1_000L

/** Hevy writes RPE as written (`8.5`); Titan stores it times ten. */
private const val RPE_SCALE = 10

/** Hevy writes body fat in percent (`15.5`); Titan stores tenths of one. */
private const val BODY_FAT_SCALE = 10
