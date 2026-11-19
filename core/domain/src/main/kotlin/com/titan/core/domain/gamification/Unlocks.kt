package com.titan.core.domain.gamification

import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.UnlockCondition
import com.titan.core.domain.model.Unlockable
import com.titan.core.domain.random.RandomSource

/**
 * Everything that unlocks for the titan — the places and the gear — and the level-up draw.
 *
 * Their keys share one stored table, so they must not collide; a test holds that.
 */
object Unlocks {

    /** Every unlockable this build knows about. */
    val ALL: List<Unlockable> = TitanPlace.entries + Gear.entries

    /** The keys a level-up can drop, in catalogue order. */
    val LEVEL_UP_POOL: List<String> = ALL.filter { it.condition == UnlockCondition.LevelUpDrop }.map { it.key }

    /**
     * The items [levelsGained] level-ups drop: one per level, drawn without repeats from what
     * the pool still holds, until it is empty.
     *
     * @param dropped what has already dropped, and cannot drop again.
     */
    fun levelUpDrops(
        levelsGained: Int,
        dropped: Set<String>,
        random: RandomSource,
    ): Set<String> {
        val left = LEVEL_UP_POOL.filterNot { it in dropped }.toMutableList()
        val drawn = mutableSetOf<String>()
        repeat(levelsGained.coerceAtMost(left.size)) {
            drawn += left.removeAt(random.nextInt(left.size))
        }
        return drawn
    }
}
