package com.titan.feature.titan.dock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.gamification.level
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.repository.LoadoutRepository
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.TitanStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the dock draws: nothing without a titan, else the active titan as of now. */
sealed interface TitanDockUiState {

    /** No titan: the dock is not drawn, and the tabs are as they were before titans. */
    data object Hidden : TitanDockUiState

    /** @property name the nickname, or null to show the species' name. */
    data class Shown(
        val speciesId: String,
        val name: String?,
        val level: Int,
        val activity: TitanActivity,
        val place: TitanPlace,
        val vitals: TitanVitals,
        val alert: TitanVital?,
        val head: String?,
        val face: String?,
    ) : TitanDockUiState
}

/**
 * The scaffold's view of the active titan, for the dock above the bottom bar.
 *
 * Reads, never writes: the titan moves on its own, and the dock re-reads it on a timer so it
 * changes activity while the user is on another tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TitanDockViewModel @Inject constructor(
    private val petRepository: PetRepository,
    private val titanStateRepository: TitanStateRepository,
    private val loadoutRepository: LoadoutRepository,
) : ViewModel() {

    /** How often the dock re-reads the titan. */
    internal var refresh: Duration = 30.seconds

    val uiState: StateFlow<TitanDockUiState> =
        petRepository.observeActive()
            .flatMapLatest { pet -> if (pet == null) flowOf(TitanDockUiState.Hidden) else shown(pet) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TitanDockUiState.Hidden)

    private fun shown(pet: Pet): Flow<TitanDockUiState> {
        val state = ticker().flatMapLatest { titanStateRepository.observe(pet.id) }.filterNotNull()
        return combine(state, loadoutRepository.observeLoadout(pet.id)) { titan, loadout ->
            TitanDockUiState.Shown(
                speciesId = pet.speciesId,
                name = pet.nickname,
                level = pet.level,
                activity = titan.activity,
                place = TitanPlace.byKey(loadout[TitanPlace.LOADOUT_SLOT]) ?: TitanPlace.DEFAULT,
                vitals = titan.vitals,
                alert = TitanRules.alertFor(titan.vitals),
                head = loadout["head"],
                face = loadout["face"],
            )
        }
    }

    private fun ticker(): Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(refresh)
            }
        }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
