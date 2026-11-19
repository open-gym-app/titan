package com.titan.feature.titan.tab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.gamification.PetNickname
import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.gamification.level
import com.titan.core.domain.model.FeedEffect
import com.titan.core.domain.model.FeedResult
import com.titan.core.domain.model.Gear
import com.titan.core.domain.model.GearEntry
import com.titan.core.domain.model.PantryEntry
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.LoadoutRepository
import com.titan.core.domain.repository.PantryRepository
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.TitanStateRepository
import com.titan.core.domain.repository.UnlockRepository
import com.titan.core.domain.usecase.FeedUseCase
import com.titan.core.domain.usecase.HatchEggUseCase
import com.titan.core.domain.usecase.WearGearUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Titan tab: the active titan, what it is doing, its vitals and its supplies — or, with no
 * titan yet, the eggs waiting to hatch.
 *
 * The titan's state is advanced to now by its store on every read; the tab re-reads it every
 * [refresh] so the screen moves while it is open, as well as whenever anything is written.
 *
 * The selected supplies tab and an open rename survive process death through [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("LongParameterList")
class TitanTabViewModel @Inject constructor(
    private val petRepository: PetRepository,
    private val titanStateRepository: TitanStateRepository,
    private val pantryRepository: PantryRepository,
    private val unlockRepository: UnlockRepository,
    private val loadoutRepository: LoadoutRepository,
    private val collectedItemRepository: CollectedItemRepository,
    private val gamificationRepository: GamificationRepository,
    private val feed: FeedUseCase,
    private val hatchEgg: HatchEggUseCase,
    private val wearGear: WearGearUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** How often the open tab re-reads the titan, so it moves without anything being written. */
    internal var refresh: Duration = 30.seconds

    private val lastEffect = MutableStateFlow<FeedEffect?>(null)
    private val hatching = MutableStateFlow(false)
    private val tab = savedStateHandle.getStateFlow(TAB_KEY, SuppliesTab.FOOD.name)
    private val renameDraft = savedStateHandle.getStateFlow<String?>(RENAME_KEY, null)
    private val renameRefusal = MutableStateFlow<PetNicknameResult?>(null)

    val uiState: StateFlow<TitanTabUiState> =
        petRepository.observeActive()
            .flatMapLatest { pet -> if (pet == null) noTitan() else titan(pet) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TitanTabUiState.Loading)

    fun onEvent(event: TitanTabEvent) {
        when (event) {
            is TitanTabEvent.SelectTab -> savedStateHandle[TAB_KEY] = event.tab.name

            is TitanTabEvent.Feed -> act { petId ->
                val result = feed(petId, event.foodId)
                lastEffect.value = (result as? FeedResult.Fed)?.effect
            }

            is TitanTabEvent.ToggleGear -> act { petId ->
                lastEffect.value = null
                val worn = loadoutRepository.observeLoadout(petId).first().containsValue(event.gearKey)
                if (worn) wearGear.takeOff(petId, event.gearKey) else wearGear.wear(petId, event.gearKey)
            }

            is TitanTabEvent.Hatch -> viewModelScope.launch {
                hatching.value = true
                try {
                    hatchEgg(event.collectedItemId)
                } finally {
                    hatching.value = false
                }
            }

            // Synchronous, from what is on screen: an edit typed straight after must not be
            // overwritten by a read that lands late.
            TitanTabEvent.StartRename -> {
                renameRefusal.value = null
                savedStateHandle[RENAME_KEY] = (uiState.value as? TitanTabUiState.Titan)?.name.orEmpty()
            }

            is TitanTabEvent.EditRename -> {
                renameRefusal.value = null
                savedStateHandle[RENAME_KEY] = event.draft
            }

            TitanTabEvent.CancelRename -> closeRename()

            TitanTabEvent.SaveRename -> act { petId -> saveRename(petId) }
        }
    }

    private suspend fun saveRename(petId: Long) {
        val draft = renameDraft.value ?: return
        when (val checked = PetNickname.validate(draft)) {
            is PetNicknameResult.Valid -> {
                petRepository.rename(petId, checked.nickname)
                closeRename()
            }

            // An empty name is not a refusal: it goes back to the species' own.
            PetNicknameResult.Blank -> {
                petRepository.rename(petId, null)
                closeRename()
            }

            else -> renameRefusal.value = checked
        }
    }

    private fun closeRename() {
        renameRefusal.value = null
        savedStateHandle[RENAME_KEY] = null
    }

    private fun act(block: suspend (petId: Long) -> Unit) {
        viewModelScope.launch {
            val pet = petRepository.observeActive().first() ?: return@launch
            block(pet.id)
        }
    }

    private fun noTitan(): Flow<TitanTabUiState> =
        combine(collectedItemRepository.observeUnhatchedEggs(), hatching) { eggs, busy ->
            TitanTabUiState.NoTitan(eggs = eggs, hatching = busy)
        }

    private fun titan(pet: Pet): Flow<TitanTabUiState> {
        val state = ticker().flatMapLatest { titanStateRepository.observe(pet.id) }.filterNotNull()
        val owned = combine(
            unlockRepository.observeUnlocked(),
            gamificationRepository.observeUnlockedAchievements(),
        ) { dropped, achievements -> Owned(dropped, achievements.mapTo(mutableSetOf()) { it.achievementId }) }
        val supplies = combine(
            pantryRepository.observePantry(),
            owned,
            loadoutRepository.observeLoadout(pet.id),
        ) { pantry, unlocked, loadout -> Triple(pantry, unlocked, loadout) }
        val chrome = combine(tab, lastEffect, renameDraft, renameRefusal) { tab, effect, draft, refusal ->
            Chrome(SuppliesTab.valueOf(tab), effect, draft?.let { TitanTabUiState.RenameState(it, refusal) })
        }
        return combine(state, supplies, chrome) { titan, (pantry, unlocked, loadout), chrome ->
            build(pet, titan, pantry, unlocked, loadout, chrome)
        }
    }

    @Suppress("LongParameterList")
    private fun build(
        pet: Pet,
        state: TitanState,
        pantry: List<PantryEntry>,
        unlocked: Owned,
        loadout: Map<String, String>,
        chrome: Chrome,
    ): TitanTabUiState.Titan =
        TitanTabUiState.Titan(
            petId = pet.id,
            speciesId = pet.speciesId,
            name = pet.nickname,
            level = pet.level,
            power = TitanRules.power(pet.level, pet.stats),
            activity = state.activity,
            minutesLeft = state.endsAt?.let { ((it - state.updatedAt) / MINUTE_MILLIS).toInt() }?.takeIf { it >= 1 },
            vitals = state.vitals,
            alert = TitanRules.alertFor(state.vitals),
            head = loadout["head"],
            face = loadout["face"],
            tab = chrome.tab,
            pantry = pantry,
            // Nothing locked is shown: what the user sees is what the titan has.
            gear = Gear.entries
                .filter { it.isOwned(unlocked.dropped, unlocked.achievements) }
                .map { GearEntry(it, worn = loadout[it.slot.key] == it.key) },
            places = TitanPlace.entries.filter { it.isOwned(unlocked.dropped, unlocked.achievements) },
            lastEffect = chrome.effect,
            rename = chrome.rename,
        )

    private fun ticker(): Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(refresh)
            }
        }

    /** What the titan owns: the level-up drops stored, and the achievements held. */
    private data class Owned(
        val dropped: Set<String>,
        val achievements: Set<String>,
    )

    private data class Chrome(
        val tab: SuppliesTab,
        val effect: FeedEffect?,
        val rename: TitanTabUiState.RenameState?,
    )

    private companion object {
        const val TAB_KEY = "titan_supplies_tab"
        const val RENAME_KEY = "titan_rename_draft"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MINUTE_MILLIS = 60_000L
    }
}
