package com.titan.core.domain.plate

/**
 * One plate denomination, and how many **pairs** of it the user can get to.
 *
 * Pairs, not plates, because a barbell is loaded symmetrically and an odd plate is one nobody
 * can use: somebody with three 20 kg plates can load two of them and is looking at the third.
 * Counting in pairs makes that a property of the type rather than a division every caller has
 * to remember — and it is the number a user can actually answer, since they count what is on
 * the rack in twos.
 *
 * @property weight what one plate weighs, in milligrams — the same unit as
 *   [com.titan.core.domain.model.WorkoutSet.weight], so a loaded bar is a sum and never a
 *   conversion. Bounded at both ends by [MIN_PLATE_WEIGHT] and [MAX_PLATE_WEIGHT]: neither
 *   bound describes a plate anybody sells, they exist so an inventory typed in by hand cannot
 *   hand [PlateCalculator] a search space it has to grind through.
 * @property pairs how many pairs are available, from none to [MAX_PAIRS]. **Zero is legal and
 *   meaningful**: it is "I know about this size and do not have it", which is what a plate the
 *   user has switched off in the inventory editor looks like. Dropping the row instead would
 *   lose the size from the editor entirely.
 */
data class PlateStock(
    val weight: Long,
    val pairs: Int,
) {
    init {
        require(weight in MIN_PLATE_WEIGHT..MAX_PLATE_WEIGHT) {
            "a plate must weigh between $MIN_PLATE_WEIGHT and $MAX_PLATE_WEIGHT mg, was $weight"
        }
        require(pairs in 0..MAX_PAIRS) { "a plate count must be 0..$MAX_PAIRS pairs, was $pairs" }
    }

    /** What every pair of this plate adds to the bar, which is twice what one weighs. */
    val totalWeight: Long get() = weight * pairs * PLATES_PER_PAIR

    companion object {

        /** Both plates of a pair. Named so the multiplication below reads as what it is. */
        const val PLATES_PER_PAIR: Int = 2

        /**
         * The lightest plate this app admits: 0.1 kg, which is under the 0.25 kg and 0.25 lb
         * micro-plates that are the lightest anybody manufactures.
         *
         * The bound is not about plausibility, it is about [PlateCalculator]'s search: the
         * solver works in steps of the greatest common divisor of the denominations, so one
         * absurd 1 mg plate would turn a 200 kg bar into a hundred million states.
         */
        const val MIN_PLATE_WEIGHT: Long = 100_000L

        /** The heaviest: 100 kg, comfortably above the 50 kg plates a strongman gym stocks. */
        const val MAX_PLATE_WEIGHT: Long = 100_000_000L

        /** As many pairs of one size as any rack holds. See [MIN_PLATE_WEIGHT] for why bounds exist. */
        const val MAX_PAIRS: Int = 20
    }
}
