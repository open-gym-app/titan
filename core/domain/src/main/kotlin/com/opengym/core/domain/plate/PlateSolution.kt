package com.opengym.core.domain.plate

import kotlin.math.abs

/**
 * What [PlateCalculator] answers: either the target cannot be built at all, or here is what
 * goes on each end of the bar and here is how close that gets.
 *
 * ### Nothing here is rounded, and nothing here is hidden
 *
 * The whole point of the type is that a target the rack cannot make is a *reported* outcome
 * rather than a silently adjusted number. A calculator that answers 100 kg with the loading
 * for 97.5 kg and says nothing has told the user they lifted a weight they did not. So the
 * shortfall is on the result, always, and the screen has to decide what to do with it — it
 * cannot be dropped by accident.
 *
 * The one thing that *is* absorbed is [WEIGHT_TOLERANCE], which is not rounding — it is the
 * difference between two exact answers to the same question.
 */
sealed interface PlateSolution {

    /** The weight the user asked for, in milligrams. Echoed back so a result stands alone. */
    val target: Long

    /** The empty bar it was to be built on, in milligrams. */
    val barWeight: Long

    /**
     * The target is lighter than the empty bar.
     *
     * Its own case rather than a [Loaded] with no plates and a negative shortfall, because it
     * is a different sentence: "you cannot get there from here" instead of "you are 2.5 kg
     * short". The user has either mistyped, or is on a movement this bar is the wrong tool
     * for, and either way the answer is the bar's weight and not a plate diagram.
     */
    data class BelowBar(
        override val target: Long,
        override val barWeight: Long,
    ) : PlateSolution

    /**
     * The bar, loaded — with [shortfall] saying how much of the target the rack could not make.
     *
     * @property perSide what goes on **one** end, heaviest first, as counts of each size. The
     *   other end is the mirror of it; there is no second list, because two lists that are
     *   meant to be identical are two lists that can stop being identical.
     */
    data class Loaded(
        override val target: Long,
        override val barWeight: Long,
        val perSide: List<PlateCount>,
    ) : PlateSolution {

        /** What one end of the bar carries. */
        val perSideWeight: Long = perSide.sumOf { it.totalWeight }

        /** What the bar actually weighs once loaded: the bar, plus both ends. */
        val achieved: Long = barWeight + perSideWeight * PlateStock.PLATES_PER_PAIR

        /**
         * How much of the target is missing, in milligrams. Zero when the target was reached.
         *
         * Reported rather than rounded away, and reported *short* rather than over: the solver
         * will not add a plate that takes the bar past what was asked for, because overshooting
         * is the one failure a lifter cannot see — an extra pair of 2.5s looks exactly like the
         * pair that was meant to be there, and the set goes into the log at the weight they
         * typed.
         *
         * A miss inside [WEIGHT_TOLERANCE], in either direction, reads as zero. See that
         * constant for why the difference exists at all and why calling it a shortfall would be
         * the misleading answer rather than the honest one.
         */
        val shortfall: Long =
            (target - achieved).takeIf { abs(it) > WEIGHT_TOLERANCE } ?: 0L

        /** The target was hit. The only case a screen draws without a caveat. */
        val isExact: Boolean = shortfall == 0L

        /** The bar goes on the rack naked. True for a target of exactly the bar's weight, and
         *  for one so close to it that no plate fits under the gap. */
        val isBarOnly: Boolean = perSide.isEmpty()
    }

    companion object {

        /**
         * How far a loading may sit from the target and still count as hitting it: one gram.
         *
         * Not a rounding allowance — a reconciliation between two exact numbers. Every weight in
         * the app is milligrams, and an imperial weight is never a whole number of them: 2.5 lb
         * is 1,133,980.925 mg. A bar the user typed as "45" and a 45 lb plate on the rack are
         * therefore two independent roundings of the same quantity, and they disagree by a few
         * milligrams. Without an allowance, that disagreement costs a *whole plate* — a target
         * three milligrams under what two 45s make comes back as "87.5 lb, 2.5 lb short" for a
         * completely ordinary 225 lb bench.
         *
         * A gram is a thousand times the largest disagreement this arithmetic can produce and a
         * hundred-thousandth of [PlateStock.MIN_PLATE_WEIGHT], so it can never bridge a gap the
         * user could see or swallow a plate they could feel.
         */
        const val WEIGHT_TOLERANCE: Long = 1_000L
    }
}

/**
 * A number of plates of one size, on one end of the bar.
 *
 * @property weight what one of them weighs, in milligrams.
 * @property count how many go on this end. Always at least one — a size that contributes
 *   nothing is left out of [PlateSolution.Loaded.perSide] rather than listed as a zero, so the
 *   legend on screen is what the user picks up and not the catalogue of what they own.
 */
data class PlateCount(val weight: Long, val count: Int) {
    init {
        require(count > 0) { "a plate count in a solution must be positive, was $count" }
    }

    /** What this many plates of this size add to one end of the bar. */
    val totalWeight: Long get() = weight * count
}
