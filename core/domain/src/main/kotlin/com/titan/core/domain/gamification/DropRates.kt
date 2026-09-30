package com.titan.core.domain.gamification

import com.titan.core.domain.model.Rarity

/**
 * How often each [Rarity] drops — the whole of the loot economy's tuning, as one table.
 *
 * A value, not a `when`. [DropTableEvaluator] walks this map and does no arithmetic of its own,
 * so retuning the economy is editing the numbers below and nothing else: there is no second
 * place where "epic is rare" is stated, and no branch that has to be kept in step with the
 * weights. The same call [XpCalculator] makes with its award constants and [Achievements] makes
 * with its thresholds.
 *
 * ### Reading the table: the remainder is nothing
 *
 * One roll over `0 until` [TOTAL_WEIGHT] decides both questions #107 asks — whether anything
 * drops, and what. The bands are laid end to end from the commonest, and a roll landing past the
 * last of them is a **no drop**. So the weights below are out of a thousand, they sum to
 * [DROP_WEIGHT], and the odds of a trigger paying out anything at all are that sum over
 * [TOTAL_WEIGHT] — 20%, on the values as they stand. Widening a band therefore makes drops
 * commoner rather than only redistributing them, which is the property that keeps the table
 * readable: every number is a probability on its own, not a share of a total that moves when its
 * neighbour does.
 *
 * Making the no-drop case the remainder rather than an entry of its own is what keeps that true.
 * A `NOTHING to 800` line would have to be edited every time any other line was, and would be
 * the one number in the table nobody could sanity-check by reading it.
 *
 * ### The values are starting defaults
 *
 * Like [XpCalculator]'s constants and [StreakCalculator.STREAK_GRACE_HOURS], nothing here has
 * been validated against real usage. The relationships are the part worth arguing about; each
 * band is a half to a quarter of the one below it, the fall steepening at the top, which on a
 * lifter training three times a week puts a common every three weeks or so, an uncommon every
 * six, a rare a handful of times a year, an epic somewhere in it, and a legendary in the years
 * — long enough that the top of the table stays something to hear about rather than something
 * to expect.
 *
 * Each number is named and read from nowhere else, so retuning one is an edit to a single line,
 * and a test that wants "the last roll that still drops a rare" asks the table rather than
 * restating the number.
 */
object DropRates {

    /**
     * The denominator every weight below is out of — a roll is `0 until` this.
     *
     * A thousand rather than a hundred so the top band can be priced below one percent without
     * the table growing decimals. Integer weights are what let a test state a boundary exactly.
     */
    const val TOTAL_WEIGHT = 1000

    /**
     * What each band is worth out of [TOTAL_WEIGHT], commonest first.
     *
     * The order is [Rarity]'s own and is load-bearing twice over: the evaluator lays the bands
     * out in this order, and a reader checks the curve by seeing the numbers descend. It is held
     * to [Rarity.entries] by a test, so a band added to the enum and not priced here fails
     * rather than becoming an unobtainable rarity.
     */
    val WEIGHTS: Map<Rarity, Int> = mapOf(
        Rarity.COMMON to 100,
        Rarity.UNCOMMON to 60,
        Rarity.RARE to 30,
        Rarity.EPIC to 8,
        Rarity.LEGENDARY to 2,
    )

    /**
     * The share of [TOTAL_WEIGHT] that drops something — the odds a trigger pays out at all.
     *
     * Derived rather than written down, so it cannot fall out of step with the bands it is the
     * sum of. A test holds it at or below [TOTAL_WEIGHT]: past that, the last bands would be
     * priced into a range no roll can reach and would silently never drop.
     */
    val DROP_WEIGHT: Int = WEIGHTS.values.sum()
}
