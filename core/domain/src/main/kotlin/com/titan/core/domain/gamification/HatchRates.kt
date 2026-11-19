package com.titan.core.domain.gamification

import com.titan.core.domain.model.Rarity

/**
 * How often an egg hatches into each [Rarity] — one table, priced per band.
 *
 * The shape of [DropRates], with one difference: **a hatch always produces something**, so there
 * is no remainder that pays out nothing and the weights sum to [TOTAL_WEIGHT] exactly. A test
 * holds that sum.
 *
 * Priced per rarity rather than per species so that adding a species widens its band and moves
 * no odds.
 */
object HatchRates {

    /** The denominator every weight below is out of — a hatch's first draw is `0 until` this. */
    const val TOTAL_WEIGHT = 100

    /**
     * What each band is worth out of [TOTAL_WEIGHT], commonest first.
     *
     * The order is [Rarity]'s own and is load-bearing: the bands are laid end to end in it, so
     * on these values 49 is the last common and 50 the first uncommon.
     */
    val WEIGHTS: Map<Rarity, Int> = mapOf(
        Rarity.COMMON to 50,
        Rarity.UNCOMMON to 28,
        Rarity.RARE to 15,
        Rarity.EPIC to 6,
        Rarity.LEGENDARY to 1,
    )

    /**
     * The band a first draw of [roll] lands in.
     *
     * @param roll a value in `0 until` [TOTAL_WEIGHT].
     * @throws IllegalArgumentException outside that range: there is no "nothing" share for a
     *   stray roll to fall into.
     */
    fun rarityFor(roll: Int): Rarity {
        require(roll in 0 until TOTAL_WEIGHT) { "a hatch roll must be in 0 until $TOTAL_WEIGHT, was $roll" }
        var remaining = roll
        for ((rarity, weight) in WEIGHTS) {
            if (remaining < weight) return rarity
            remaining -= weight
        }
        error("the hatch weights sum to less than $TOTAL_WEIGHT")
    }
}
