package com.opengym.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.repository.LocalDataRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The settings screen's state, and the writes behind it.
 *
 * ### Stored state and screen state are combined, not merged
 *
 * The preferences and the data footprint are read from their repositories and never held
 * here; [screenState] holds only what the *screen* owns — which dialog is open, whether a
 * wipe is running, an outcome waiting to be shown. Combining the three at the edge is what
 * makes a setting change appear the moment it is stored rather than when a copy is updated,
 * so there is no path where a control shows a value the store disagrees with. It is also why
 * a failed write needs no rollback: nothing was optimistically changed to roll back.
 *
 * ### Every write can fail, and none of them fail silently
 *
 * `UserPreferencesRepository` throws [java.io.IOException] when the store cannot be written,
 * and its contract is explicit that swallowing that leaves the user looking at a control that
 * moved and did not save. Here the control does *not* move — it renders the stored value —
 * so the failure has to be said out loud, which is [SettingsMessage.SAVE_FAILED].
 *
 * Both [write] and the wipe catch [Throwable] rather than a type. The store is not the only
 * thing that can refuse a write — two of the setters validate their argument and throw
 * [IllegalArgumentException] for a value that got past the dialog — and the wipe runs across
 * four stores whose failures have four different types. Narrower catches here would mean the
 * ways a settings screen actually breaks reach the user as a crash instead of a sentence.
 * Cancellation is re-thrown in both, as it must be: a cancelled `viewModelScope` is the
 * screen going away, not a write that failed.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val localDataRepository: LocalDataRepository,
) : ViewModel() {

    /** What the screen owns, as opposed to what the stores do. */
    private val screenState = MutableStateFlow(ScreenState())

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferencesRepository.observe(),
        localDataRepository.observeFootprint(),
        screenState,
    ) { preferences, footprint, screen ->
        SettingsUiState(
            preferences = preferences,
            footprint = footprint,
            dialog = screen.dialog,
            isDeleting = screen.isDeleting,
            message = screen.message,
        )
    }.stateIn(
        scope = viewModelScope,
        // Long enough to survive a rotation without re-reading two stores, short enough that
        // a screen left in the background is not holding a database observer open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SettingsUiState(),
    )

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.DialogRequested -> screenState.update { it.copy(dialog = event.dialog) }
            SettingsEvent.DialogDismissed -> dismissDialog()
            is SettingsEvent.ThemeSelected -> setTheme(event.theme)
            is SettingsEvent.UnitSystemSelected -> setUnitSystem(event.unitSystem)
            is SettingsEvent.RestDurationSelected -> setRestDuration(event.duration)
            is SettingsEvent.BarWeightSelected -> setBarWeight(event.milligrams)
            is SettingsEvent.TrainingGoalSelected -> setTrainingGoal(event.goal)
            is SettingsEvent.OneRepMaxFormulaSelected -> setOneRepMaxFormula(event.formula)
            SettingsEvent.DeleteAllConfirmed -> deleteAll()
            SettingsEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    private fun setTheme(theme: ThemePreference) = write { userPreferencesRepository.setTheme(theme) }

    private fun setUnitSystem(unitSystem: UnitSystem) = write { userPreferencesRepository.setUnitSystem(unitSystem) }

    private fun setRestDuration(duration: Duration) =
        write { userPreferencesRepository.setDefaultRestDuration(duration) }

    private fun setBarWeight(milligrams: Long) = write { userPreferencesRepository.setBarWeight(milligrams) }

    private fun setTrainingGoal(goal: TrainingGoal) = write { userPreferencesRepository.setTrainingGoal(goal) }

    private fun setOneRepMaxFormula(formula: OneRepMaxFormula) =
        write { userPreferencesRepository.setOneRepMaxFormula(formula) }

    /**
     * Closes the picker, then stores the choice.
     *
     * In that order, and both unconditionally: the dialog is closed by *making a choice*, not
     * by the choice landing on disk, so a slow write does not leave the picker up looking
     * unresponsive. If the write then fails the user gets [SettingsMessage.SAVE_FAILED] and
     * the row still shows the stored value, which is the truth.
     *
     * "Fails" includes a value the repository *rejects*, not only a store it cannot write:
     * `setBarWeight` and `setDefaultRestDuration` both `require` a positive argument, and a
     * setting that will not save is the same event to a user however the write refused it.
     */
    private fun write(block: suspend () -> Unit) {
        dismissDialog()
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update { it.copy(message = SettingsMessage.SAVE_FAILED) }
            }
        }
    }

    private fun dismissDialog() = screenState.update { it.copy(dialog = null) }

    /**
     * Erases everything, with the dialog left up and inert until it is over.
     *
     * Guarded against a second run while one is in flight. Two concurrent wipes are not
     * harmful — the second finds nothing to delete — but the guard is what stops the button
     * being pressable during the seconds the first one takes, which is the window in which a
     * user who thinks nothing happened presses it again.
     */
    private fun deleteAll() {
        if (screenState.value.isDeleting) return
        screenState.update { it.copy(isDeleting = true) }

        viewModelScope.launch {
            val message = try {
                localDataRepository.deleteAll()
                SettingsMessage.DELETE_SUCCEEDED
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                SettingsMessage.DELETE_FAILED
            }
            screenState.update { it.copy(isDeleting = false, dialog = null, message = message) }
        }
    }

    /** The half of the state that belongs to the screen rather than to a store. */
    private data class ScreenState(
        val dialog: SettingsDialog? = null,
        val isDeleting: Boolean = false,
        val message: SettingsMessage? = null,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
