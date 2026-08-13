package com.opengym.feature.history.plate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.feature.history.navigation.NO_SUGGESTED_TARGET
import com.opengym.feature.history.navigation.PlateCalculatorDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The plate calculator's state: a target the screen owns, and the equipment the store does.
 *
 * ### The target is not stored, and that is deliberate
 *
 * It lives here for as long as the screen is open and is gone afterwards. A remembered target
 * would be wrong more often than right — the number a lifter wants is the one for the set they
 * are about to do, not the one they looked up last week — and it would put a write on the path
 * of every stepper press. What *is* stored is the equipment, which genuinely does not change
 * between sessions.
 *
 * ### The bar and the rack are written through, never mirrored
 *
 * Both editors write to [UserPreferencesRepository] and say nothing more. The store re-emits and
 * the screen redraws from that, so a failed write leaves the control showing what is actually
 * saved rather than what the user tried to save — which is the whole reason those writes are
 * allowed to throw. See `UserPreferencesRepository`.
 */
@HiltViewModel
class PlateCalculatorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    /** The weight the caller wanted looked up, or null when the screen was opened cold. */
    private val suggestedTarget: Long? = savedStateHandle
        .toRoute<PlateCalculatorDestination>()
        .target
        .takeIf { it != NO_SUGGESTED_TARGET }

    private val screenState = MutableStateFlow(ScreenState(target = suggestedTarget))

    val uiState: StateFlow<PlateCalculatorUiState> = combine(
        preferencesRepository.observe(),
        screenState,
    ) { preferences, screen ->
        PlateCalculatorUiState(
            // Falling back rather than seeding: until the user has touched a stepper there is
            // nothing to remember, so the opening weight can be derived from the equipment as it
            // stands. Setting it once from the first emission would freeze it against a bar
            // weight the user then changes on this very screen.
            target = screen.target ?: openingTarget(preferences),
            preferences = preferences,
            sheet = screen.sheet,
            message = screen.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = PlateCalculatorUiState(),
    )

    fun onEvent(event: PlateCalculatorEvent) {
        when (event) {
            is PlateCalculatorEvent.TargetStepped -> step(event.delta)
            is PlateCalculatorEvent.TargetEntered ->
                screenState.update { it.copy(target = event.milligrams.coerceAtLeast(0), sheet = null) }

            is PlateCalculatorEvent.BarWeightSelected -> save { setBarWeight(event.milligrams) }
            is PlateCalculatorEvent.PlatePairsSelected -> save {
                setPlateInventory(uiState.value.preferences.plates.withPairs(event.weight, event.pairs))
            }
            PlateCalculatorEvent.InventoryReset -> save { setPlateInventory(null) }

            is PlateCalculatorEvent.SheetRequested -> screenState.update { it.copy(sheet = event.sheet) }
            PlateCalculatorEvent.SheetDismissed -> screenState.update { it.copy(sheet = null) }
            PlateCalculatorEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    /**
     * Move the target by [delta], never below nothing.
     *
     * Read off [uiState] rather than off [screenState], because until the first press the target
     * is the derived opening weight and `screenState.target` is still null — stepping from null
     * would jump the user to `delta` from zero.
     */
    private fun step(delta: Long) {
        val stepped = (uiState.value.target + delta).coerceAtLeast(0)
        screenState.update { it.copy(target = stepped) }
    }

    /**
     * Run a settings write, and say so if it fails.
     *
     * Nothing is updated on success: the store re-emits, and that is what redraws the screen. On
     * failure the control has already snapped back to the stored value for the same reason, so
     * all that is left to do is tell the user why nothing moved.
     *
     * [Throwable] rather than [java.io.IOException] alone, for the reason the exercise detail
     * screen's saves catch broadly: a store that cannot be written fails in more than one way,
     * and all of them are the same event to somebody who just pressed a `+`.
     */
    private fun save(write: suspend UserPreferencesRepository.() -> Unit) {
        viewModelScope.launch {
            try {
                preferencesRepository.write()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update { it.copy(message = PlateCalculatorMessage.SAVE_FAILED) }
            }
        }
    }

    /**
     * What the screen opens on when nobody suggested a weight: a plate a side.
     *
     * The first working weight in both systems and the one number every lifter recognises — 60
     * or 70 kg on a metric rack, 135 lb on an imperial one — rather than the empty bar, which is
     * a correct answer to a question nobody opens a calculator to ask. It follows the equipment,
     * so a user with a 15 kg bar and no 25s still opens on something they could load.
     */
    private fun openingTarget(preferences: UserPreferences): Long {
        val heaviest = preferences.plates.available.firstOrNull()?.weight ?: 0L
        return preferences.barWeight + heaviest * PLATES_IN_A_PAIR
    }

    /** What the screen owns, as opposed to what the store does. */
    private data class ScreenState(
        val target: Long? = null,
        val sheet: PlateCalculatorSheet? = null,
        val message: PlateCalculatorMessage? = null,
    )

    private companion object {
        /**
         * Long enough to survive a rotation without re-reading, short enough that a backgrounded
         * screen is not holding a store observer open. The library's number, for the same reason.
         */
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds

        /** One on each end. Named so [openingTarget] reads as "a plate a side". */
        const val PLATES_IN_A_PAIR = 2
    }
}
