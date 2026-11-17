package com.titan.core.domain.importer.plain

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.DayMonthOrder
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.csv.CsvHeader
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.csv.MalformedCsvException
import java.time.ZoneId
import java.util.Locale

/**
 * Reads Titan's own CSV import format: the file a user writes, or converts a spreadsheet or another
 * app's export into, when Titan has no parser for the app their history is in.
 *
 * Every other parser reads a format somebody else defined and nobody documented. This one reads a
 * format Titan defines, so `docs/import-csv.md` is its specification, written for the person
 * filling the file in, and this class is held to it. The two kinds of file it reads are summed up
 * here; the document has every column, the values each accepts and examples.
 *
 * ### A workout file: one row per set
 *
 * Recognised by a `workout_date` and an `exercise` column. Every row is one set: the workout it
 * belongs to (`workout_date`, and optionally `workout_time`, `workout_name`, `workout_duration`,
 * `workout_notes`, `workout_calories`, `workout_rating`, repeated on each of its rows), the exercise
 * (`exercise`, optionally `equipment`, `muscle`, `exercise_notes`, `superset`) and the set itself
 * (`set_type`, `reps`, `weight`, `weight_unit`, `duration`, `distance`, `distance_unit`, `rpe`,
 * `rest`, `avg_heart_rate`, `max_heart_rate`, `notes`). See [WorkoutColumns] for what becomes what.
 *
 * ### A body measurement file: one row per measurement
 *
 * Recognised by a `measurement_date` column, with `weight`, `body_fat`, the nine circumferences
 * Titan records and `notes` beside it. See [MeasurementColumns].
 *
 * ### Written by a person, read with a person's habits in mind
 *
 * - **Column names** are matched without regard to case, and with any run of spaces, hyphens or
 *   other punctuation read as one underscore, so `Workout Date` is `workout_date`. Columns may come
 *   in any order, and a column the format does not define is ignored: spreadsheets add them.
 * - **Units** go in a column's header, `weight (kg)` or `weight_kg`, or in a unit column beside it
 *   (`weight_unit`), or are left to the user's own setting, [ImportContext.unitSystem]. A unit in
 *   the header that is not one Titan reads is [ParseResult.Malformed], because no figure under it
 *   could be read; a parenthesis after a column that has no unit (`rpe (1-10)`) is ignored.
 * - **Dates** are ISO 8601 (`2026-11-02`, `2026-11-02 18:30`, with an offset or without), or the
 *   locale's own with the year last, as a spreadsheet writes them back out, read day first or month
 *   first as the file's own dates prove ([SourceValues.dayMonthOrder]).
 * - **The rest of CSV**, as [CsvReader] reads it: a comma, semicolon, tab or bar between fields,
 *   a byte order mark, decimal commas, quoted line breaks.
 *
 * ### Why the required columns are named as they are
 *
 * `date` and `exercise` would be the obvious names, and they are exactly the columns FitNotes' and
 * Fitbod's exports start with. A FitNotes export offered as a plain CSV file would then be read as
 * one, and lose its categories and units, where its own parser keeps them; so the date columns say
 * what they date. No other parser recognises a file of this format, and this one recognises none of
 * theirs; each parser's tests hold it to that.
 *
 * ### What a damaged file costs
 *
 * A header without the columns above is not this format at all, and is [ParseResult.NotRecognised].
 * Past that, one bad row is skipped rather than costing the history: a row whose date does not read
 * or that names no exercise is left out, and a set or a measurement whose figure is not a number,
 * or whose unit is not one Titan reads, is passed on with nothing recorded, so the planner refuses
 * it and counts it in the report. A figure that only describes a set — an RPE, a heart rate, a rest
 * — costs nothing but itself. Only a file whose every row is unreadable, or whose CSV breaks in a
 * way no row can be recovered from, is [ParseResult.Malformed].
 */
class PlainCsvParser : HistoryParser {

    override fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult =
        source.open().use { stream ->
            val reader = CsvReader.open(stream)
            val layout = readHeader(reader)?.let { header ->
                WorkoutColumns.of(header, context) ?: MeasurementColumns.of(header, context)
            }
            val problem = layout?.problem
            when {
                layout == null -> ParseResult.NotRecognised

                problem != null -> ParseResult.Malformed(problem)

                else -> try {
                    layout.read(reader.records().toList())
                } catch (failure: MalformedCsvException) {
                    ParseResult.Malformed(failure.message.orEmpty())
                }
            }
        }

    /**
     * The first record as a header, its names made canonical, or null when there is none —
     * including when it is not CSV at all, which is a file that is not this format rather than a
     * broken one.
     */
    private fun readHeader(reader: CsvReader): CsvHeader? =
        try {
            reader.readRecord()?.let { columns -> CsvHeader(columns.map(::canonicalColumn)) }
        } catch (_: MalformedCsvException) {
            null
        }
}

/** One of the two kinds of file the format has, as its header describes it. */
internal sealed interface PlainCsvLayout {

    /** Why no figure in one of the columns can be read, its header naming no unit Titan reads; null when none. */
    val problem: String?

    /** The history [records], every row after the header, hold. */
    fun read(records: List<List<String>>): ParseResult
}

/**
 * [column] as the format names it: lower case, with every run of anything but letters and digits
 * one underscore, and a trailing parenthesis kept apart — `Weight (KG)` is `weight (KG)`, `Workout
 * Date` is `workout_date`, `Avg. Heart Rate` is `avg_heart_rate`.
 *
 * The parenthesis keeps its own spelling and its space before it, which is what
 * [CsvHeader.qualified] reads a unit from.
 */
internal fun canonicalColumn(column: String): String {
    val match = QUALIFIED_COLUMN.matchEntire(column.trim())
    val name = (match?.groupValues?.get(1) ?: column)
        .lowercase(Locale.ROOT)
        .replace(NAME_SEPARATORS, "_")
        .trim('_')
    return match?.let { "$name (${it.groupValues[2].trim()})" } ?: name
}

private val QUALIFIED_COLUMN = Regex("(.*?)\\s*\\(([^()]*)\\)")
private val NAME_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

/**
 * A figure's column, and the unit its header names.
 *
 * @property name the column's name, as [CsvHeader.get] reads it.
 * @property unit the unit its header names, or null when it names none or names one [unit] could
 *   not read.
 * @property label what its header named the unit, or null when it named none.
 */
internal class UnitColumn<U : Any>(
    val name: String,
    val unit: U?,
    private val label: String?,
) {
    /** Why no figure under it can be read — its header names a unit that is none — or null. */
    val problem: String?
        get() = if (label != null && unit == null) "the column \"$name\" is not in a unit Titan reads" else null
}

/**
 * The column holding [name]'s figures, as `name`, `name (unit)` or `name_unit`, with the unit its
 * header names; null when the header has none.
 *
 * A parenthesis is the place the format gives a unit, so one that names no unit is kept, for
 * [UnitColumn.problem] to report. An underscore is a spelling some users and exports prefer, but it
 * is also how a spreadsheet names a column of its own (`weight_total`), so a suffix counts only when
 * it names a unit, and anything else is a column the format does not define.
 */
internal fun <U : Any> CsvHeader.unitColumn(
    name: String,
    parse: (String) -> U?,
): UnitColumn<U>? {
    qualified(name)?.let { column -> return UnitColumn(column.name, column.qualifier?.let(parse), column.qualifier) }
    return suffixed(name).firstNotNullOfOrNull { column ->
        column.qualifier?.let(parse)?.let { unit -> UnitColumn(column.name, unit, column.qualifier) }
    }
}

/**
 * The column called [name], with or without a parenthesis after it, which is ignored; null when
 * the header has none.
 */
internal fun CsvHeader.plainColumn(name: String): String? = qualified(name)?.name

/**
 * When a workout started, or a measurement was taken, from its row's [date] and [time] cells, in
 * epoch milliseconds; null when they do not read.
 *
 * A time in a column of its own is the time, and the date's own, if it has one, is not: a
 * spreadsheet formats a date cell as a date and a time, and writes `2026-11-02 00:00` for a day the
 * user typed no time on. Without that column, the date's own time is read, and a date with none
 * starts at noon, [SourceValues.localDateAtNoon], the same instant on every import.
 */
internal fun startOf(
    date: String?,
    time: String?,
    zone: ZoneId,
    order: DayMonthOrder?,
): Long? =
    when {
        date == null -> null
        time != null -> SourceValues.localDateAt(date.substringBefore(' ').substringBefore('T'), time, zone, order)
        else -> SourceValues.localDateTime(date, zone, order) ?: SourceValues.localDateAtNoon(date, zone, order)
    }
