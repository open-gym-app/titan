package com.titan.core.domain.importer.stronglifts

import com.titan.core.domain.importer.DayMonthOrder
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.csv.CsvHeader
import java.time.Duration
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The layout StrongLifts exports today: one row per exercise of a workout, its sets in `Set N
 * (Reps)` and `Set N (KG)` column pairs. See [StrongLiftsParser] for the columns and what each
 * becomes.
 */
internal class ExerciseRows private constructor(
    private val header: CsvHeader,
    private val zone: ZoneId,
    private val history: StrongLiftsHistory,
    private val dateColumn: String,
    private val sets: List<SetColumns>,
    private val bodyWeightColumn: String?,
    private val bodyWeightUnit: SourceWeightUnit,
    private val durationColumn: String?,
    private val secondsPerDurationUnit: Double?,
    private val startColumn: String?,
    private val endColumn: String?,
    private val repsSummaryColumn: String?,
    private val timeSummaryColumn: String?,
    override val problem: String?,
) : StrongLiftsLayout {

    override fun read(records: List<List<String>>): ParseResult {
        val order = dayMonthOrder(header, records, dateColumn)
        val readRows = records.count { read(it, order) }
        return history.result(records.size, readRows)
    }

    /** Reads [record] into the history; whether it had a date and an exercise to read. */
    private fun read(
        record: List<String>,
        order: DayMonthOrder?,
    ): Boolean {
        val date = header[record, dateColumn]
        val startedAt = SourceValues.localDateAt(date, cell(record, startColumn), zone, order)
            ?: SourceValues.localDateAtNoon(date, zone, order)
            ?: return false
        val exercise = header[record, EXERCISE] ?: return false

        history.bodyWeight(startedAt, cell(record, bodyWeightColumn), bodyWeightUnit)
        history.workout(startedAt, header[record, WORKOUT_NAME]) { durationSeconds(record) }
            .line(history.exercise(exercise), sets(record), SourceValues.note(header[record, NOTES]))
        return true
    }

    /**
     * The sets [record] performed. An exercise StrongLifts times, with a time summary and no rep
     * summary, has its seconds in the reps columns.
     */
    private fun sets(record: List<String>): List<ImportedSet> {
        val timed = cell(record, timeSummaryColumn) != null && cell(record, repsSummaryColumn) == null
        return sets.mapNotNull { set ->
            StrongLiftsHistory.performedSet(
                count = header[record, set.reps],
                load = cell(record, set.weight),
                unit = set.unit ?: history.userUnit,
                timed = timed,
            )
        }
    }

    /**
     * How long [record]'s workout lasted: the span from its start to its end, past midnight if it
     * ends earlier than it starts, unless the recorded duration agrees with that to within the
     * minute the clock times are rounded to — then the recorded figure, which is the more precise.
     * Either alone when the other is missing.
     */
    private fun durationSeconds(record: List<String>): Long? {
        val recorded = secondsPerDurationUnit?.let { scale ->
            SourceValues.decimal(cell(record, durationColumn))?.let { (it * scale).roundToLong() }
        }
        val start = SourceValues.timeOfDay(cell(record, startColumn))
        val end = SourceValues.timeOfDay(cell(record, endColumn))
        val span = if (start != null && end != null) {
            Duration.between(start, end).seconds.let { if (it < 0) it + SECONDS_PER_DAY else it }
        } else {
            null
        }
        return when {
            recorded != null && span != null && abs(recorded - span) < SECONDS_PER_MINUTE -> recorded
            else -> span ?: recorded
        }
    }

    private fun cell(
        record: List<String>,
        column: String?,
    ): String? = column?.let { header[record, it] }

    /** One set's columns: the reps, and the weight with the unit its header names, when it has one. */
    private class SetColumns(
        val reps: String,
        val weight: String?,
        val unit: SourceWeightUnit?,
    )

    companion object {

        /** [header]'s layout, or null when it is not this layout's. */
        fun of(
            header: CsvHeader,
            context: ImportContext,
        ): ExerciseRows? {
            val date = header.qualified(DATE)
            val numbered = header.columns.mapNotNull { column ->
                SET_COLUMN.matchEntire(column)?.let { set ->
                    val (number, qualifier) = set.destructured
                    Triple(column, number.toInt(), qualifier.trim())
                }
            }
            val reps = numbered.filter { (_, _, qualifier) -> qualifier.equals(REPS, ignoreCase = true) }
            if (date == null || EXERCISE !in header || reps.isEmpty()) return null

            val weights = (numbered - reps.toSet()).associate { (column, number, qualifier) ->
                number to (column to SourceWeightUnit.parse(qualifier))
            }
            val sets = reps.sortedBy { it.second }.map { (column, number, _) ->
                SetColumns(column, weights[number]?.first, weights[number]?.second)
            }
            val bodyWeight = header.qualified(BODY_WEIGHT)
            val bodyWeightUnit = bodyWeight?.qualifier?.let(SourceWeightUnit::parse)
            val duration = header.qualified(DURATION)
            val durationScale = duration?.qualifier
                ?.let { DURATION_UNITS[it.lowercase(Locale.ROOT)] }
                ?: SECONDS_PER_HOUR.takeIf { duration?.qualifier == null }

            val unreadable = weights.values.firstOrNull { (_, unit) -> unit == null }?.first
                ?: bodyWeight?.name?.takeIf { bodyWeight.qualifier != null && bodyWeightUnit == null }
                ?: duration?.name?.takeIf { durationScale == null }

            return ExerciseRows(
                header = header,
                zone = context.zone,
                history = StrongLiftsHistory(context),
                dateColumn = date.name,
                sets = sets,
                bodyWeightColumn = bodyWeight?.name,
                bodyWeightUnit = bodyWeightUnit ?: SourceWeightUnit.of(context.unitSystem),
                durationColumn = duration?.name,
                secondsPerDurationUnit = durationScale,
                startColumn = header.qualified(START_TIME)?.name,
                endColumn = header.qualified(END_TIME)?.name,
                repsSummaryColumn = summary(header, REPS_SUMMARY),
                timeSummaryColumn = summary(header, TIME_SUMMARY),
                problem = unreadable?.let { "the column \"$it\" is not in a unit Titan reads" },
            )
        }

        /**
         * The summary column [key] names however the export spells it — `Sets×Reps`, `SetsxReps`,
         * `Sets x Reps` — or null when the header has none.
         */
        private fun summary(
            header: CsvHeader,
            key: String,
        ): String? = header.columns.firstOrNull { summaryKey(it) == key }

        private fun summaryKey(column: String): String =
            column.lowercase(Locale.ROOT).replace('×', 'x').filterNot(Char::isWhitespace)

        /** `Set 1 (Reps)`, `Set 1 (KG)`, ` Set 1 (LB)`: the set's number and what its column holds. */
        private val SET_COLUMN = Regex("Set\\s*(\\d{1,4})\\s*\\(([^()]*)\\)", RegexOption.IGNORE_CASE)

        private const val SECONDS_PER_HOUR = 3_600.0
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_DAY = 86_400L

        /** What a duration column's header may name, lowercased, to the seconds in one: StrongLifts writes `hours`. */
        private val DURATION_UNITS = mapOf(
            "hours" to SECONDS_PER_HOUR,
            "hour" to SECONDS_PER_HOUR,
            "h" to SECONDS_PER_HOUR,
            "hrs" to SECONDS_PER_HOUR,
            "minutes" to SECONDS_PER_MINUTE.toDouble(),
            "min" to SECONDS_PER_MINUTE.toDouble(),
            "seconds" to 1.0,
            "s" to 1.0,
        )

        private const val DATE = "Date"
        private const val EXERCISE = "Exercise"
        private const val WORKOUT_NAME = "Workout Name"
        private const val BODY_WEIGHT = "Body Weight"
        private const val DURATION = "Duration"
        private const val START_TIME = "Start Time"
        private const val END_TIME = "End Time"
        private const val NOTES = "Notes"
        private const val REPS = "Reps"
        private const val REPS_SUMMARY = "setsxreps"
        private const val TIME_SUMMARY = "setsxtime"
    }
}
