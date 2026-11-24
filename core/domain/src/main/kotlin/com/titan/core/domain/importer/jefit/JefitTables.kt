package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceLengthUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.importer.csv.CsvSections
import kotlin.math.roundToLong

/** One row of a JEFIT table, read by column name. */
internal class JefitRow(
    private val header: CsvHeader,
    private val record: List<String>,
) {
    /** The row's value in [column], trimmed, or null when it is blank or the table has no such column. */
    operator fun get(column: String): String? = header[record, column]

    /** Whether the row's table has [column] at all, whatever this row holds in it. */
    fun has(column: String): Boolean = column in header

    /** The value in [column] as a whole number, rounded, or null when it is not a number. */
    fun whole(column: String): Long? = SourceValues.decimal(get(column))?.roundToLong()
}

/**
 * [this] sorted by [key], read once a row rather than once a comparison, since it parses a
 * figure. The sort is stable, so rows with the same key keep their file order.
 */
internal fun List<JefitRow>.sortedByOnce(key: (JefitRow) -> Long): List<JefitRow> =
    map { it to key(it) }.sortedBy { it.second }.map { it.first }

/**
 * The rows of a JEFIT export, looked up by section and by the columns a table's header has.
 *
 * A section can hold tables of several shapes — ROUTINES holds routines, their days and the days'
 * exercises, each under its own header — so a table is found by the columns that make it what it
 * is rather than by its position. Every lookup in a section something is imported from also
 * counts the rows it read, so that a file whose sections hold rows under no header this parser
 * knows can be told apart from one that is merely empty; see [unreadable].
 */
internal class JefitTables(private val sections: CsvSections) {

    private var importedRowsRead = 0

    /** Every row of [section] under a header that has all of [columns], in file order. */
    fun rows(
        section: String,
        vararg columns: String,
    ): List<JefitRow> =
        sections.tables(section)
            .filter { table -> table.header.containsAll(columns.asList()) }
            .flatMap { table -> table.rows.map { JefitRow(table.header, it) } }
            .also { if (section in JefitSection.IMPORTED) importedRowsRead += it.size }

    /**
     * Whether the sections something is imported from hold rows and the lookups so far read none
     * of them: every table there is under a header without the columns JEFIT writes.
     */
    val unreadable: Boolean
        get() = importedRowsRead == 0 && JefitSection.IMPORTED.any(::holdsRows)

    private fun holdsRows(section: String): Boolean = sections.tables(section).any { it.rows.isNotEmpty() }
}

/**
 * The units a JEFIT account records in, from the SETTING section: `mass`, `" lbs"` or `" kg"`, for
 * every weight the user typed, and `length`, `" inches"` or `" cm"`, for every circumference.
 *
 * @property distance the unit a distance packed into an exercise log is read in. JEFIT's own
 *   per-set table writes miles (`distance_mi`) whatever the account's units, but no export seen
 *   says what the packed figure is in for an account in metric units. An imperial account's packed
 *   distances match its miles exactly, so the packed figure is taken to follow the account's
 *   units, as its weights do: miles beside inches (or pounds), kilometres beside centimetres (or
 *   kilograms).
 */
internal class JefitUnits(
    val weight: SourceWeightUnit,
    val length: SourceLengthUnit,
    val distance: SourceDistanceUnit,
) {
    companion object {

        /** [tables]' units, each falling back to the user's own where SETTING does not say. */
        fun of(
            tables: JefitTables,
            context: ImportContext,
        ): JefitUnits {
            val setting = tables.rows(JefitSection.SETTING).firstOrNull()
            val weight = setting?.get(JefitColumn.MASS)?.let(SourceWeightUnit::parse)
            val length = setting?.get(JefitColumn.LENGTH)?.let(SourceLengthUnit::parse)
            val imperial = when {
                length != null -> length == SourceLengthUnit.INCH
                weight != null -> weight == SourceWeightUnit.POUND
                else -> null
            }
            return JefitUnits(
                weight = weight ?: SourceWeightUnit.of(context.unitSystem),
                length = length ?: SourceLengthUnit.of(context.unitSystem),
                distance = when (imperial) {
                    true -> SourceDistanceUnit.MILE
                    false -> SourceDistanceUnit.KILOMETRE
                    null -> SourceDistanceUnit.of(context.unitSystem)
                },
            )
        }
    }
}

/** The sections of a JEFIT export, by the names its markers give them. */
internal object JefitSection {
    const val SETTING = "SETTING"
    const val PROFILE = "PROFILE"
    const val ROUTINES = "ROUTINES"
    const val WORKOUT_SESSIONS = "WORKOUT SESSIONS"
    const val EXERCISE_LOGS = "EXERCISE LOGS"
    const val EXERCISE_SET_LOGS = "EXERCISE SET LOGS"
    const val CARDIO_EXERCISE_LOGS = "CARDIO EXERCISE LOGS"
    const val CUSTOM_EXERCISES = "CUSTOM EXERCISES"
    const val NOTES = "NOTES"

    /**
     * Every section a JEFIT export has been seen to hold, including the ones nothing is imported
     * from. A sectioned file with none of these is somebody else's.
     */
    val ALL: Set<String> = setOf(
        SETTING,
        PROFILE,
        ROUTINES,
        WORKOUT_SESSIONS,
        EXERCISE_LOGS,
        EXERCISE_SET_LOGS,
        "EXERCISE RECORDS",
        "CARDIO LOGS",
        CARDIO_EXERCISE_LOGS,
        CUSTOM_EXERCISES,
        "FAVORITE EXERCISES",
        NOTES,
    )

    /** The sections something is imported from. */
    val IMPORTED: List<String> = listOf(PROFILE, ROUTINES, WORKOUT_SESSIONS, EXERCISE_LOGS, CUSTOM_EXERCISES)
}

/** The column names this parser reads, as JEFIT writes them. Headers are matched without regard to case. */
internal object JefitColumn {
    const val ID = "_id"
    const val MASS = "mass"
    const val LENGTH = "length"

    // WORKOUT SESSIONS
    const val START = "starttime"

    /** A misspelling of [START] that one converter reports seeing; accepted in its place. */
    const val START_MISSPELT = "starttimie"
    const val END = "endtime"
    const val TOTAL_TIME = "total_time"
    const val DAY_ID = "day_id"
    const val CALORIES = "calories"

    // EXERCISE LOGS
    const val LOGS = "logs"
    const val DATE = "mydate"
    const val EXERCISE_ID = "eid"
    const val SYSTEM = "belongSys"
    const val EXERCISE_NAME = "ename"
    const val SESSION = "belongsession"
    const val INTERVALS = "interval_logs"

    // EXERCISE SET LOGS
    const val LOG_ID = "exercise_log_id"
    const val SET_INDEX = "set_index"
    const val WEIGHT_LBS = "weight_lbs"
    const val REPS = "reps"
    const val DISTANCE_MI = "distance_mi"
    const val DURATION = "duration"
    const val SET_TYPE = "set_type"

    // CARDIO EXERCISE LOGS
    const val CARDIO_LOG_ID = "el_id"

    // CUSTOM EXERCISES
    const val NAME = "name"
    const val BODY_PART = "bodypart"
    const val DESCRIPTION = "description"

    // ROUTINES
    const val PACKAGE = "package"
    const val DAY = "belongplan"
    const val ROUTINE_EXERCISE_ID = "exercise_id"
    const val ROUTINE_EXERCISE_NAME = "exercisename"
    const val SET_COUNT = "setcount"
    const val REST = "timer"
    const val TARGET_REPS = "targetrep"
    const val SORT = "mysort"
    const val SUPERSET = "superset"
    const val INTERVAL_TIME = "interval_time"
    const val INTERVAL_UNIT = "interval_unit"
    const val INTERVAL_ENABLED = "interval_time_enabled"

    // NOTES
    const val NOTE = "mynote"
    const val NOTE_TITLE = "title"

    // PROFILE
    const val WEIGHT = "weight"
    const val BODY_FAT = "fatpercent"
    const val CHEST = "chest"
    const val ARMS = "arms"
    const val WAIST = "waist"
    const val CALVES = "calves"
    const val HIPS = "hips"
    const val THIGHS = "thighs"
    const val SHOULDERS = "shoulders"
    const val NECK = "neck"
    const val FOREARMS = "forearms"
    const val LOG_TIME = "logTime"
}
