package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.SourceDistanceUnit
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.model.SetType
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Unpacks the sets of one JEFIT exercise log.
 *
 * ### The packed string
 *
 * JEFIT keeps every set of a log in one text column, `logs`: pieces separated by commas, each two
 * figures joined by an `x`. For a lift the pieces are sets, `weight x reps` in the account's unit
 * (`100x5,102.5x5,105x3`), and a bodyweight set is a weight of `0`. A cardio exercise, and a hold
 * such as the plank, which JEFIT logs the same way, packs each set as five pieces whose first
 * figure is always `0`: `0xcalories,0xdistance,0xspeed,0xlaps,0xseconds`. Titan keeps the
 * distance and the time; it has no column for a set's calories, speed or laps.
 *
 * Which of the two a log is comes from the export where it says ([JefitExercises.isCardio]), and
 * otherwise from the string itself: groups of five, every weight `0`, and in every group that
 * records anything a zero among the first four figures and the largest figure last, where the
 * seconds go. A group of five zeros is a planned set that was skipped and is passed over, so a
 * plank whose first set was skipped still reads as one. A lift that looks like that would be five
 * bodyweight sets, one of them failed, ending on the most reps.
 *
 * ### What else says something about a set
 *
 * - `interval_logs`, one figure per set, is the interval timer's seconds when the exercise was
 *   done against it. A set with no reps and a timed interval is a timed hold — JEFIT logs a wall
 *   sit as `0x0` with its seconds there — and a log with no pieces and timed intervals is a list of
 *   them.
 * - EXERCISE SET LOGS, which newer exports add for recent workouts, holds one row per set: its type
 *   (`default`, `warm-up`, `drop`), and its figures in fixed units (`weight_lbs`, `distance_mi`,
 *   `duration` in seconds). When it has one row per packed set, each row gives its set its type,
 *   and a cardio set its distance and time, which it keeps more faithfully than the packed string.
 *   When the packed string holds nothing, its rows are the sets.
 *
 * ### What a piece that does not read costs
 *
 * A piece whose figures are not numbers, or a cardio group cut short, becomes a set with nothing
 * recorded, which the planner refuses and counts. A piece or group that reads and records nothing
 * — `0x0`, the empty set JEFIT leaves when a planned set was skipped — is not a set and is left out.
 */
internal class JefitSetReader(private val units: JefitUnits) {

    /**
     * The sets of [log], with [rows] its EXERCISE SET LOGS rows in set order; [cardio] when the
     * export says the exercise is cardio, and [assisted] when its weights are a counterweight
     * rather than a load.
     */
    fun sets(
        log: JefitLog,
        rows: List<JefitRow>,
        cardio: Boolean,
        assisted: Boolean,
    ): List<ImportedSet> {
        val pieces = log.packed?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty().map(::piece)
        val intervals = log.intervals?.split(',')?.map { SourceValues.decimal(it)?.roundToLong() }.orEmpty()
        val packedAsCardio = cardio || looksCardio(pieces)
        val groups = if (packedAsCardio) pieces.chunked(CARDIO_FIGURES) else pieces.map(::listOf)
        val matched = rows.takeIf { it.size == groups.size }

        return when {
            groups.isNotEmpty() -> groups.mapIndexedNotNull { index, group ->
                val row = matched?.get(index)
                if (packedAsCardio) {
                    cardioSet(group, row)
                } else {
                    strengthSet(group.single(), intervals.getOrNull(index), assisted)?.withTypeOf(row)
                }
            }

            rows.isNotEmpty() -> rows.mapNotNull { rowSet(it, assisted) }

            else -> intervals.filterNotNull().filter { it > 0 }.map { ImportedSet(duration = it) }
        }
    }

    /** One `weight x reps` piece's two figures, each null when it is not a number. */
    private class Piece(
        val first: Double?,
        val second: Double?,
    ) {
        val readable: Boolean
            get() = first != null && second != null
    }

    private fun piece(text: String): Piece {
        val figures = text.split('x', 'X')
        if (figures.size != 2) return Piece(null, null)
        return Piece(SourceValues.decimal(figures[0]), SourceValues.decimal(figures[1]))
    }

    /** Whether [pieces] read as cardio groups when nothing else in the export says; see the class doc. */
    private fun looksCardio(pieces: List<Piece>): Boolean {
        if (pieces.isEmpty() || pieces.size % CARDIO_FIGURES != 0) return false
        if (!pieces.all { it.readable && it.first == 0.0 }) return false
        val groups = pieces.chunked(CARDIO_FIGURES).map { group -> group.map { checkNotNull(it.second) } }
        // An all-zero group is a planned set that was skipped, which says nothing either way.
        val recorded = groups.filterNot { figures -> figures.all { it == 0.0 } }
        return recorded.isNotEmpty() &&
            recorded.all { figures ->
                figures.take(CARDIO_FIGURES - 1).any { it == 0.0 } && figures.last() > figures.dropLast(1).max()
            }
    }

    private fun strengthSet(
        piece: Piece,
        interval: Long?,
        assisted: Boolean,
    ): ImportedSet? {
        // An assisted set's weight is dropped unread, so a counterweight that does not read never
        // costs the set its reps.
        val weight = piece.first.takeUnless { assisted }
        val reps = piece.second
        val timed = interval?.takeIf { it > 0 }
        val milligrams = weight?.let(units.weight::toMilligrams)
        return when {
            reps == null || (weight == null && !assisted) -> ImportedSet()
            reps <= 0.0 && timed != null -> ImportedSet(weight = milligrams, duration = timed)
            reps <= 0.0 && piece.first == 0.0 -> null
            else -> ImportedSet(reps = reps.roundToInt(), weight = milligrams)
        }
    }

    /**
     * The cardio set [group] packs, with what [row] holds of it in fixed units preferred; null
     * when it records neither a distance nor a time.
     */
    private fun cardioSet(
        group: List<Piece>,
        row: JefitRow?,
    ): ImportedSet? {
        if (!group.all(Piece::readable)) return ImportedSet()
        val packed = group.takeIf { it.size == CARDIO_FIGURES }
        val distance = row.miles() ?: positive(packed?.get(DISTANCE)?.second)?.let(units.distance::toCentimetres)
        val duration = row.seconds() ?: positive(packed?.get(SECONDS)?.second)?.roundToLong()
        return when {
            distance != null || duration != null -> ImportedSet(
                setType = setType(row),
                duration = duration,
                distance = distance,
            )

            // A group cut short that holds a figure is a set that lost it, for the planner to count.
            packed == null && group.any { it.second != 0.0 } -> ImportedSet()

            else -> null
        }
    }

    /** The set [row] alone records, for a log whose packed string holds none. */
    private fun rowSet(
        row: JefitRow,
        assisted: Boolean,
    ): ImportedSet? {
        val reps = SourceValues.decimal(row[JefitColumn.REPS])
        val pounds = SourceValues.decimal(row[JefitColumn.WEIGHT_LBS])
        val distance = row.miles()
        val duration = row.seconds()
        return when {
            reps != null && reps > 0.0 -> ImportedSet(
                setType = setType(row),
                reps = reps.roundToInt(),
                weight = pounds?.let(::fromPounds)?.takeUnless { assisted },
            )

            distance != null || duration != null -> ImportedSet(
                setType = setType(row),
                duration = duration,
                distance = distance,
            )

            else -> null
        }
    }

    /**
     * [pounds] in milligrams. JEFIT writes a set row's weight in pounds to four decimals whatever
     * the account's unit, so 40 kg arrives as `88.1849`. For an account in kilograms the result is
     * rounded to the gram, which puts back the figure the user typed: four decimals of a pound are
     * finer than a gram, and nobody loads a bar in fractions of one.
     */
    private fun fromPounds(pounds: Double): Long {
        val milligrams = SourceWeightUnit.POUND.toMilligrams(pounds)
        return if (units.weight == SourceWeightUnit.KILOGRAM) {
            (milligrams / MILLIGRAMS_PER_GRAM).roundToLong() * MILLIGRAMS_PER_GRAM.toLong()
        } else {
            milligrams
        }
    }

    private fun ImportedSet.withTypeOf(row: JefitRow?): ImportedSet =
        if (row == null) this else copy(setType = setType(row))

    private fun JefitRow?.miles(): Long? =
        positive(SourceValues.decimal(this?.get(JefitColumn.DISTANCE_MI)))
            ?.let(SourceDistanceUnit.MILE::toCentimetres)

    private fun JefitRow?.seconds(): Long? =
        positive(SourceValues.decimal(this?.get(JefitColumn.DURATION)))?.roundToLong()

    private fun positive(figure: Double?): Double? = figure?.takeIf { it > 0.0 }

    private fun setType(row: JefitRow?): SetType =
        row?.get(JefitColumn.SET_TYPE)?.let { SET_TYPES[ExerciseMatcher.normalised(it)] } ?: SetType.NORMAL

    private companion object {

        /** How many pieces JEFIT packs one cardio set into. */
        const val CARDIO_FIGURES = 5

        /** Where in a cardio group the distance is. */
        const val DISTANCE = 1

        /** Where in a cardio group the seconds are. */
        const val SECONDS = 4

        const val MILLIGRAMS_PER_GRAM = 1_000.0

        /**
         * EXERCISE SET LOGS' `set_type`, by [ExerciseMatcher.normalised] name. `default` and
         * anything unknown are a normal set.
         */
        val SET_TYPES: Map<String, SetType> = mapOf(
            "warm up" to SetType.WARMUP,
            "warmup" to SetType.WARMUP,
            "drop" to SetType.DROP_SET,
            "drop set" to SetType.DROP_SET,
            "dropset" to SetType.DROP_SET,
            "failure" to SetType.FAILURE,
        )
    }
}
