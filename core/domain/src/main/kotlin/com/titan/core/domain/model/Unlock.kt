package com.titan.core.domain.model

/**
 * How a place or a piece of gear comes to be the titan's. Data, so each one names its own way in.
 *
 * Nothing locked is ever shown: what the user sees is what the titan has.
 */
sealed interface UnlockCondition {

    /** Owned the moment a titan hatches. */
    data object FromHatch : UnlockCondition

    /**
     * Owned once the user holds the achievement [id]. Derived from the achievement rather than
     * stored, so it is kept for good — an achievement is never taken back — and appears the
     * moment a titan hatches, however long ago the achievement was earned.
     */
    data class Achievement(val id: String) : UnlockCondition

    /**
     * Owned once it drops: each time the user levels up while owning a titan, one item still
     * missing from this pool is drawn at random and kept.
     */
    data object LevelUpDrop : UnlockCondition
}

/** Something that unlocks: a [key] its owner is known by, and the [condition] that earns it. */
interface Unlockable {
    val key: String
    val condition: UnlockCondition

    /**
     * Whether this is owned, given the level-up drops stored and the achievements held.
     *
     * @param dropped the keys stored by level-up drops.
     * @param achievements the ids of the achievements the user holds.
     */
    fun isOwned(
        dropped: Set<String>,
        achievements: Set<String>,
    ): Boolean =
        when (val condition = condition) {
            UnlockCondition.FromHatch -> true
            is UnlockCondition.Achievement -> condition.id in achievements
            UnlockCondition.LevelUpDrop -> key in dropped
        }
}
