package com.titan.feature.titan.tab

import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.FeedEffect
import com.titan.core.domain.model.GearEntry
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
     * The active titan.
     *
     * @property name the nickname, or null to show the species' name.
     * @property minutesLeft how long the current activity has to run, or null when it ends on a
     *   condition (rest) or is nearly over.
     * @property alert the one vital to flag, if any.
     * @property lastEffect what the last feed did, shown as a chip until the next action.
     * @property places every place, owned or not.
     */
    data class Titan(
        val petId: Long,
        val speciesId: String,
        val name: String?,
        val level: Int,
        val power: Int,
        val activity: TitanActivity,
        val minutesLeft: Int?,
        val vitals: TitanVitals,
        val alert: TitanVital?,
        val head: String?,
        val face: String?,
        val tab: SuppliesTab,
        val pantry: List<PantryEntry>,
        val gear: List<GearEntry>,
        val places: List<Pair<TitanPlace, Boolean>>,
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

/** What the Titan tab can be asked to do. */
sealed interface TitanTabEvent {
    data class Choose(val activity: TitanActivity) : TitanTabEvent
    data class SelectTab(val tab: SuppliesTab) : TitanTabEvent
    data class Feed(val foodId: String) : TitanTabEvent
    data class ToggleGear(val gearKey: String) : TitanTabEvent
    data class Hatch(val collectedItemId: Long) : TitanTabEvent
    data object StartRename : TitanTabEvent
    data class EditRename(val draft: String) : TitanTabEvent
    data object SaveRename : TitanTabEvent
    data object CancelRename : TitanTabEvent
}
