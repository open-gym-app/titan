package com.titan.core.domain.model

/**
 * Where on the titan a piece of gear is worn. One piece per slot.
 *
 * The canvas draws the head and the face; the other slots hold the items it lists but does not
 * draw yet (#291's open decision), so they are named here and drawn when the art has them.
 *
 * @property key the stable id a loadout row stores. Pinned by a test; not validated on restore.
 */
enum class GearSlot(val key: String) {
    HEAD("head"),
    FACE("face"),
    HANDS("hands"),
    NECK("neck"),
    BACK("back"),
    CHEST("chest"),
}

/**
 * One piece of gear. **Gear is cosmetic**: it changes how the titan looks, never how strong it
 * is — no stat, vital or power figure reads it.
 *
 * Earned through an achievement or dropped on a level-up, through the same [Unlockable] contract
 * the habitat uses. A titan hatches with none.
 *
 * @property key the stable id the loadout and the unlock table store, and the string-resource key.
 */
enum class Gear(
    override val key: String,
    val slot: GearSlot,
    override val condition: UnlockCondition,
) : Unlockable {
    HEADBAND("headband", GearSlot.HEAD, UnlockCondition.Achievement("first_session")),
    SHADES("shades", GearSlot.FACE, UnlockCondition.Achievement("ten_sessions")),
    CROWN("crown", GearSlot.HEAD, UnlockCondition.Achievement("first_record")),
    CHALK("chalk", GearSlot.HANDS, UnlockCondition.LevelUpDrop),
    CAP("cap", GearSlot.HEAD, UnlockCondition.LevelUpDrop),
    CAPE("cape", GearSlot.BACK, UnlockCondition.LevelUpDrop),
    SCARF("scarf", GearSlot.NECK, UnlockCondition.LevelUpDrop),
    MEDAL("medal", GearSlot.CHEST, UnlockCondition.LevelUpDrop),
    ;

    companion object {

        /** The gear [key] names, or null for one this build does not know. */
        fun byKey(key: String): Gear? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One piece of gear the user owns, as the supplies grid and the inventory draw it. Gear not
 * owned is never drawn.
 *
 * @property worn whether this pet has it on.
 */
data class GearEntry(
    val gear: Gear,
    val worn: Boolean,
)

/** The answer to putting a piece of gear on. Every answer is returned, none thrown. */
sealed interface WearResult {

    /** On, in its slot, replacing whatever was there ([replaced], if anything). */
    data class Worn(val replaced: Gear?) : WearResult

    /** Not owned yet. Never offered on screen; refused if asked for anyway. */
    data object Locked : WearResult

    /** There is no such gear. */
    data object NotGear : WearResult

    /** There is no such pet. */
    data object NotFound : WearResult
}
