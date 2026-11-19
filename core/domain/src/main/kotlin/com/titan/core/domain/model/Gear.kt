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
 * Owned from hatch, or unlocked by the user's training, through the same [Unlockable] contract
 * and table the habitat uses.
 *
 * @property key the stable id the loadout and the unlock table store, and the string-resource key.
 */
enum class Gear(
    override val key: String,
    val slot: GearSlot,
    override val condition: UnlockCondition,
) : Unlockable {
    HEADBAND("headband", GearSlot.HEAD, UnlockCondition.FromHatch),
    SHADES("shades", GearSlot.FACE, UnlockCondition.FromHatch),
    CROWN("crown", GearSlot.HEAD, UnlockCondition.FromHatch),
    CHALK("chalk", GearSlot.HANDS, UnlockCondition.Level(level = 20)),
    CAP("cap", GearSlot.HEAD, UnlockCondition.DistanceKm(kilometres = 100)),
    CAPE("cape", GearSlot.BACK, UnlockCondition.Level(level = 40)),
    SCARF("scarf", GearSlot.NECK, UnlockCondition.SparsWon(count = 10)),
    MEDAL("medal", GearSlot.CHEST, UnlockCondition.Level(level = 100)),
    ;

    /** Whether this is owned, given the unlock keys stored. */
    fun isOwned(unlocked: Set<String>): Boolean = condition == UnlockCondition.FromHatch || key in unlocked

    companion object {

        /** The gear [key] names, or null for one this build does not know. */
        fun byKey(key: String): Gear? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One piece of gear as the supplies grid and the inventory draw it.
 *
 * @property owned whether the user has it; a locked one shows what it needs ([Gear.condition]).
 * @property worn whether this pet has it on.
 */
data class GearEntry(
    val gear: Gear,
    val owned: Boolean,
    val worn: Boolean,
)

/** The answer to putting a piece of gear on. Every answer is returned, none thrown. */
sealed interface WearResult {

    /** On, in its slot, replacing whatever was there ([replaced], if anything). */
    data class Worn(val replaced: Gear?) : WearResult

    /** Not owned yet: it unlocks on [needs]. */
    data class Locked(val needs: UnlockCondition) : WearResult

    /** There is no such gear. */
    data object NotGear : WearResult

    /** There is no such pet. */
    data object NotFound : WearResult
}
