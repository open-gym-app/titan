package com.titan.core.domain.gamification

import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.random.RandomSource

/**
 * The food a finished session pays an owned titan.
 *
 * ### Guaranteed, small, and sized by effort
 *
 * Food is a consumable the titan needs steadily, so it cannot ride on a one-in-five roll: every
 * session with paid sets pays one to three items. "Paid" is [SessionXp.isPaidSet] under
 * [SessionXp.MAX_PAID_SETS], the caps the user's own XP and the pet's growth already use, so
 * warm-ups and sets past the cap do not raise the drop.
 *
 * ### Its own pool
 *
 * Each item is drawn uniformly from [Collectibles.FOOD], after the trophy roll and from numbers
 * of its own, so it moves no trophy or egg odds.
 *
 * The sizes are starting defaults; the canvas shows one outcome, not a rule.
 */
class FoodDrop(
    private val random: RandomSource = RandomSource.SYSTEM,
    private val pantry: List<Collectible> = Collectibles.FOOD,
) {

    /** How many items a session of [paidSets] paid sets earns. */
    fun sizeFor(paidSets: Int): Int =
        when {
            paidSets <= 0 -> 0
            paidSets < SECOND_ITEM_AT -> 1
            paidSets < THIRD_ITEM_AT -> 2
            else -> MAX_ITEMS
        }

    /** The food a session of [sets] earns, drawn one item at a time. */
    fun roll(sets: List<WorkoutSet>): List<Collectible> {
        if (pantry.isEmpty()) return emptyList()
        val paid = sets.count(SessionXp::isPaidSet).coerceAtMost(SessionXp.MAX_PAID_SETS)
        return List(sizeFor(paid)) { pantry[random.nextInt(pantry.size)] }
    }

    companion object {

        /** Paid sets from which a session earns a second item. */
        const val SECOND_ITEM_AT = 8

        /** Paid sets from which it earns a third. */
        const val THIRD_ITEM_AT = 16

        /** The most food one session pays. */
        const val MAX_ITEMS = 3
    }
}
