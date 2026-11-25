package com.titan.feature.titan.tab

import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.FeedEffect
import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.GearEntry
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.PantryEntry
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals

/** The three supplies tabs, in the canvas's order. */
enum class SuppliesTab { FOOD, GEAR, HABITAT }

/** What the Titan tab shows. */
sealed interface TitanTabUiState {

    data object Loading : TitanTabUiState

    /**
     * No titan yet: what a titan is, and — when the user holds any — the eggs waiting to hatch.
     */
    data class NoTitan(
        val eggs: List<CollectedItem>,
        val hatching: Boolean = false,
    ) : TitanTabUiState

    /**
     * The active titan. It picks what it does itself; the tab only shows it.
     *
     * @property name the nickname, or null to show the species' name.
     * @property alert the one vital to flag, if any.
     * @property lastEffect what the last feed did, shown as a chip until the next action.
     * @property slots every slot gear can go in, with what is worn there and what the titan owns
     *   for it. Nothing locked is shown.
     * @property slot the slot the gear tab has open.
     * @property roster every titan the user owns, the active one included, to switch between.
     * @property eggs the eggs still waiting to hatch.
     * @property places the habitats the titan owns.
     * @property habitat the one its stage is drawn on.
     */
    data class Titan(
        val petId: Long,
        val speciesId: String,
        val name: String?,
        val level: Int,
        val power: Int,
        val activity: TitanActivity,
        val vitals: TitanVitals,
        val alert: TitanVital?,
        val head: String?,
        val face: String?,
        val tab: SuppliesTab,
        val pantry: List<PantryEntry>,
        val slots: List<SlotEntry>,
        val slot: GearSlot,
        val places: List<TitanPlace>,
        val habitat: TitanPlace,
        val roster: List<RosterEntry> = emptyList(),
        val eggs: List<CollectedItem> = emptyList(),
        val hatching: Boolean = false,
        val lastEffect: FeedEffect? = null,
        val rename: RenameState? = null,
    ) : TitanTabUiState

    /**
     * The rename dialog, open.
     *
     * @property refusal why the draft cannot be saved, once the user has tried.
     */
    data class RenameState(
        val draft: String,
        val refusal: PetNicknameResult? = null,
    )
}

/**
 * One slot of the titan's body as the gear tab draws it.
 *
 * @property worn what is on it, if anything.
 * @property owned the pieces the titan owns for it.
 */
data class SlotEntry(
    val slot: GearSlot,
    val worn: Gear?,
    val owned: List<GearEntry>,
)

/**
 * A titan the user owns, as the switcher lists it.
 *
 * @property name the nickname, or null to show the species' name.
 */
data class RosterEntry(
    val petId: Long,
    val speciesId: String,
    val name: String?,
    val level: Int,
    val active: Boolean,
)

/** What the Titan tab can be asked to do. */
sealed interface TitanTabEvent {
    data class SwitchTitan(val petId: Long) : TitanTabEvent

    data class SelectTab(val tab: SuppliesTab) : TitanTabEvent
    data class Feed(val foodId: String) : TitanTabEvent
    data class SelectSlot(val slot: GearSlot) : TitanTabEvent
    data class ToggleGear(val gearKey: String) : TitanTabEvent
    data class SelectHabitat(val place: TitanPlace) : TitanTabEvent
    data class Hatch(val collectedItemId: Long) : TitanTabEvent
    data object StartRename : TitanTabEvent
    data class EditRename(val draft: String) : TitanTabEvent
    data object SaveRename : TitanTabEvent
    data object CancelRename : TitanTabEvent
}
