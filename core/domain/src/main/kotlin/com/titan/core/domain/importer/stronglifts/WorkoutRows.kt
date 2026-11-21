package com.titan.core.domain.importer.stronglifts

import com.titan.core.domain.importer.DayMonthOrder
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.WeightFigure
import com.titan.core.domain.importer.csv.CsvHeader
import java.time.ZoneId

/**
 * The layout StrongLifts' earlier versions exported: one row per workout, each exercise a group of
 * columns — its name, its weight in kilograms and in pounds, and its sets' reps. See
 * [StrongLiftsParser] for the columns and what each becomes.
 *
 * The header repeats `Weight (KG)` and `Set 1` in every group, so these columns are read by their
 * position in the group rather than by name, and the groups are found by the header: a group starts
 * at each column that is neither a weight nor a set.
 */
internal class WorkoutRows private constructor(
    private val header: CsvHeader,
    private val zone: ZoneId,
    private val history: StrongLiftsHistory,
    private val groups: List<Group>,
    private val bodyWeightColumn: String?,
    private val bodyWeightUnit: SourceWeightUnit,
    override val problem: String?,
) : StrongLiftsLayout {

    override fun read(records: List<List<String>>): ParseResult {
        val order = dayMonthOrder(header, records, DATE)
        val readRows = records.count { read(it, order) }
        return history.result(records.size, readRows)
    }

    /** Reads [record] into the history; whether it had a date and an exercise to read. */
    private fun read(
        record: List<String>,
        order: DayMonthOrder?,
    ): Boolean {
        val startedAt = SourceValues.localDateAtNoon(header[record, DATE], zone, order) ?: return false
        val bodyWeight = bodyWeightColumn?.let { header[record, it] }
        // The unit the body weight is written in is the one the user logged their lifts in.
        val unit = WeightFigure.parse(bodyWeight)?.unit ?: bodyWeightUnit
        val exercises = groupsOf(record).mapNotNull { it.read(record, unit) }
        if (exercises.isEmpty()) return false

        history.bodyWeight(startedAt, bodyWeight, bodyWeightUnit)
        val workout = history.workout(startedAt, workoutName(header[record, WORKOUT])) { null }
        workout.note(SourceValues.note(header[record, NOTE]))
        exercises.forEach { (name, sets) -> workout.line(history.exercise(name), sets, rowNote = null) }
        return true
    }

    /**
     * The groups [record] has: the header's, then as many more of the last one's shape as the
     * fields past the header hold, for the assistance work StrongLifts writes beyond the header.
     */
    private fun groupsOf(record: List<String>): List<Group> {
        val last = groups.last()
        val extra = generateSequence(last.start + last.width) { it + last.width }
            .takeWhile { it < record.size }
            .map { start -> last.copy(start = start) }
        return groups + extra
    }

    /**
     * One exercise's columns, at [start]: its name there, then [weights] and [sets] at offsets from
     * it, [width] columns in all.
     */
    private data class Group(
        val start: Int,
        val width: Int,
        val weights: List<Pair<Int, SourceWeightUnit?>>,
        val sets: List<Int>,
    ) {
        /**
         * The exercise [record] names in this group and the sets performed of it, the weight read in
         * [unit] when the group has a column in it and in whichever unit has a figure otherwise; null
         * when the group names no exercise.
         */
        fun read(
            record: List<String>,
            unit: SourceWeightUnit,
        ): Pair<String, List<ImportedSet>>? {
            val name = cell(record, start) ?: return null
            val (load, loadUnit) = weights
                .sortedByDescending { (_, columnUnit) -> columnUnit == unit }
                .firstNotNullOfOrNull { (offset, columnUnit) -> cell(record, start + offset)?.let { it to columnUnit } }
                ?: (null to null)
            val sets = sets.mapNotNull { offset ->
                StrongLiftsHistory.performedSet(cell(record, start + offset), load, loadUnit ?: unit, timed = false)
            }
            return name to sets
        }

        private fun cell(
            record: List<String>,
            index: Int,
        ): String? = record.getOrNull(index)?.trim()?.takeIf(String::isNotEmpty)
    }

    companion object {

        /** [header]'s layout, or null when it is not this layout's. */
        fun of(
            header: CsvHeader,
            context: ImportContext,
        ): WorkoutRows? {
            val first = header.columns.indexOfFirst { EXERCISE_COLUMN.matches(it) }
            if (first < 0 || !header.containsAll(listOf(DATE, WORKOUT))) return null
            val groups = groups(header.columns, first)
            val weights = groups.flatMap { group ->
                group.weights.map { (offset, unit) -> group.start + offset to unit }
            }
            if (weights.isEmpty()) return null

            val bodyWeight = header.qualified(BODY_WEIGHT)
            val bodyWeightUnit = bodyWeight?.qualifier?.let(SourceWeightUnit::parse)
            val unreadable =
                weights.firstOrNull { (_, unit) -> unit == null }?.let { (index, _) -> header.columns[index] }
                    ?: bodyWeight?.name?.takeIf { bodyWeight.qualifier != null && bodyWeightUnit == null }

            return WorkoutRows(
                header = header,
                zone = context.zone,
                history = StrongLiftsHistory(context),
                groups = groups,
                bodyWeightColumn = bodyWeight?.name,
                bodyWeightUnit = bodyWeightUnit ?: SourceWeightUnit.of(context.unitSystem),
                problem = unreadable?.let { "the column \"$it\" is not in a unit Titan reads" },
            )
        }

        /** The exercise groups of [columns], the first starting at [first]. */
        private fun groups(
            columns: List<String>,
            first: Int,
        ): List<Group> {
            val starts = (first until columns.size).filter { index ->
                WEIGHT_COLUMN.matchEntire(columns[index]) == null && !SET_COLUMN.matches(columns[index])
            }
            return starts.mapIndexed { position, start ->
                val end = starts.getOrNull(position + 1) ?: columns.size
                val weights = mutableListOf<Pair<Int, SourceWeightUnit?>>()
                val sets = mutableListOf<Int>()
                for (index in start + 1 until end) {
                    val weight = WEIGHT_COLUMN.matchEntire(columns[index])
                    if (weight != null) {
                        weights += index - start to SourceWeightUnit.parse(weight.groupValues[1].trim())
                    } else {
                        sets += index - start
                    }
                }
                Group(start = start, width = end - start, weights = weights, sets = sets)
            }
        }

        /** The workout's name as the newer layout writes it: `Workout A` for the letter `A`. */
        private fun workoutName(written: String?): String? =
            written?.let { if (it.startsWith(WORKOUT, ignoreCase = true)) it else "$WORKOUT $it" }

        private val EXERCISE_COLUMN = Regex("Exercise\\s*\\d{1,3}", RegexOption.IGNORE_CASE)
        private val WEIGHT_COLUMN = Regex("Weight\\s*\\(([^()]*)\\)", RegexOption.IGNORE_CASE)
        private val SET_COLUMN = Regex("Set\\s*\\d{1,3}", RegexOption.IGNORE_CASE)

        private const val DATE = "Date"
        private const val NOTE = "Note"
        private const val WORKOUT = "Workout"
        private const val BODY_WEIGHT = "Body Weight"
    }
}
