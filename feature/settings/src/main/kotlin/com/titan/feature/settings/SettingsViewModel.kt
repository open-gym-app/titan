package com.titan.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.notification.NotificationPermissionState
import com.titan.core.domain.repository.LocalDataRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.usecase.RequestNotificationPermissionUseCase
import com.titan.core.ui.state.savedScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
 *
 * ### The notification permission is asked for late, and once
 *
 * Nothing here requests `POST_NOTIFICATIONS` at startup. The prompt goes up the first time the
 * user turns a notification on — a prompt with no context attached is the one most reliably
 * denied, and a denial cannot be taken back — and it can only go up once, because Android
 * shows it once. Both halves of that are [RequestNotificationPermissionUseCase], which the
 * reminder screen asks the same question of: it reads the *store* rather than the last-published
 * state for whether the prompt has been raised before, so the decision does not depend on a
 * collector having caught up, and it records the raising before the answer arrives, because a
 * user who dismisses the prompt has still been asked. Two screens deciding this separately is
 * two chances to ask twice, and the second prompt is the one Android answers with the refusal
 * the user gave the first.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val localDataRepository: LocalDataRepository,
    private val notificationPermission: NotificationPermission,
    private val requestNotificationPermission: RequestNotificationPermissionUseCase,
) : ViewModel() {

    /**
     * What the screen owns, as opposed to what the stores do.
     *
     * Saved, so an open dialog — a rest-duration picker, a plate weight, the typed confirmation
     * in front of the wipe — is still there when the user comes back (#183). The permission
     * state is the one field that must not be: it is read from Android, and Android may have
     * been told something different in the settings app the user just left for.
     */
    private val screenState = savedStateHandle.savedScreenState(viewModelScope) {
        // Read once at construction and again on every resume. There is no callback for this
        // changing, and it changes in a place the app cannot see.
        ScreenState(notificationsAllowed = notificationPermission.areNotificationsAllowed())
    }

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
            notificationPermission = NotificationPermissionState.of(
                allowed = screen.notificationsAllowed,
                runtimePermissionRequired = notificationPermission.isRuntimePermissionRequired,
                alreadyRequested = preferences.notifications.permissionRequested,
            ),
            notificationPermissionRequest = screen.notificationPermissionRequest,
        )
    }.stateIn(
        scope = viewModelScope,
        // Long enough to survive a rotation without re-reading two stores, short enough that
        // a screen left in the background is not holding a database observer open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        // Carrying the restored dialog, so one the process died with open is drawn on the first
        // frame rather than flashing shut while the settings store answers.
        initialValue = SettingsUiState(dialog = screenState.value.dialog),
    )

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.DialogRequested -> screenState.update { it.copy(dialog = event.dialog) }
            SettingsEvent.DialogDismissed -> dismissDialog()
            is SettingsEvent.ThemeSelected -> setTheme(event.theme)
            is SettingsEvent.UnitSystemSelected -> setUnitSystem(event.unitSystem)
            is SettingsEvent.RestDurationSelected -> setRestDuration(event.duration)
            is SettingsEvent.BarWeightSelected -> setBarWeight(event.milligrams)
            is SettingsEvent.TrainingGoalSelected -> setTrainingGoal(event)
            is SettingsEvent.FitnessLevelSelected -> setFitnessLevel(event.level)
            is SettingsEvent.OneRepMaxFormulaSelected -> setOneRepMaxFormula(event.formula)
            is SettingsEvent.WarmupSuggestionsToggled -> setWarmupSuggestions(event.enabled)
            is SettingsEvent.Notification -> onNotificationEvent(event)
            SettingsEvent.DeleteAllConfirmed -> deleteAll()
            SettingsEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    /**
     * The notifications group, handled as the one conversation it is.
     *
     * Split out of [onEvent] rather than inlined into it: three of the four are steps in the
     * same exchange with the system permission, and they read as such next to each other in a
     * way they do not among the value pickers.
     */
    private fun onNotificationEvent(event: SettingsEvent.Notification) {
        when (event) {
            is SettingsEvent.Notification.Toggled -> setNotificationEnabled(event.kind, event.enabled)

            SettingsEvent.Notification.PermissionRequested -> write { maybeRequestNotificationPermission() }

            SettingsEvent.Notification.PermissionRequestShown ->
                screenState.update { it.copy(notificationPermissionRequest = false) }

            SettingsEvent.Notification.PermissionRechecked -> recheckNotificationPermission()
        }
    }

    private fun setTheme(theme: ThemePreference) = writeAndDismiss { userPreferencesRepository.setTheme(theme) }

    private fun setUnitSystem(unitSystem: UnitSystem) =
        writeAndDismiss { userPreferencesRepository.setUnitSystem(unitSystem) }

    private fun setRestDuration(duration: Duration) =
        writeAndDismiss { userPreferencesRepository.setDefaultRestDuration(duration) }

    private fun setBarWeight(milligrams: Long) = writeAndDismiss { userPreferencesRepository.setBarWeight(milligrams) }

    /**
     * The goal, then whatever CUSTOM asked for — three targeted writes rather than one, per the
     * repository's read-whole-write-one contract, and in that order so that a failure on the
     * second leaves a goal stored with the range it had rather than a range with no goal.
     */
    private fun setTrainingGoal(event: SettingsEvent.TrainingGoalSelected) =
        writeAndDismiss {
            userPreferencesRepository.setTrainingGoal(event.goal)
            event.customRepRange?.let { userPreferencesRepository.setCustomRepRange(it) }
            event.customIncrement?.let { userPreferencesRepository.setCustomIncrement(it) }
        }

    private fun setFitnessLevel(level: FitnessLevel) =
        writeAndDismiss { userPreferencesRepository.setFitnessLevel(level) }

    private fun setOneRepMaxFormula(formula: OneRepMaxFormula) =
        writeAndDismiss { userPreferencesRepository.setOneRepMaxFormula(formula) }

    // `write`, not `writeAndDismiss`: a switch has no dialog to close behind it.
    private fun setWarmupSuggestions(enabled: Boolean) =
        write { userPreferencesRepository.setWarmupSuggestions(enabled) }

    /**
     * Stores a notification switch, and asks for the permission behind it if this is the first
     * one to go on.
     *
     * In that order, and the write is unconditional: the preference is what the user *wants*,
     * and it is recorded whether or not the permission is ever granted. Turning one off asks
     * nothing — there is nothing to deliver — which is the other half of the rule that the
     * prompt only ever follows an act of enabling.
     */
    private fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write {
        userPreferencesRepository.setNotificationEnabled(kind, enabled)
        if (enabled) maybeRequestNotificationPermission()
    }

    /**
     * Asks the screen to raise the system prompt, if raising it would show the user anything.
     *
     * Whether it would is [RequestNotificationPermissionUseCase]'s to answer, and it is asked
     * rather than re-implemented here: the reminder screen turns the same permission on from
     * its own switch, and two copies of this rule are two chances for one of them to prompt a
     * user Android has already answered for.
     *
     * The two things around the call are this screen's own. The guard is a prompt already
     * waiting to be raised, which the store cannot report yet; the publish is the group
     * rendering "Blocked" over a permission that may have been granted elsewhere since the last
     * resume.
     */
    private suspend fun maybeRequestNotificationPermission() {
        // A prompt already waiting for the screen to raise it is the answer to this question,
        // and the store cannot say so yet — two switches turned on inside one write's round
        // trip would otherwise both decide to ask, and the second launch is a prompt Android
        // discards while the first is still up.
        if (screenState.value.notificationPermissionRequest) return

        // Published rather than only consulted: this is a fresh read of a value that changes
        // outside the app, and dropping it would leave the group rendering "Blocked" over a
        // permission the user has just granted somewhere else until the next resume. The use
        // case reads it again for its own decision, which is a `NotificationManagerCompat`
        // lookup and cheaper than a second return value would be to thread through it.
        val allowed = notificationPermission.areNotificationsAllowed()
        screenState.update { it.copy(notificationsAllowed = allowed) }

        if (requestNotificationPermission()) {
            screenState.update { it.copy(notificationPermissionRequest = true) }
        }
    }

    /** Re-reads the system's answer. See [SettingsEvent.Notification.PermissionRechecked]. */
    private fun recheckNotificationPermission() =
        screenState.update { it.copy(notificationsAllowed = notificationPermission.areNotificationsAllowed()) }

    /**
     * Closes the picker, then stores the choice.
     *
     * In that order, and both unconditionally: the dialog is closed by *making a choice*, not
     * by the choice landing on disk, so a slow write does not leave the picker up looking
     * unresponsive. If the write then fails the user gets [SettingsMessage.SAVE_FAILED] and
     * the row still shows the stored value, which is the truth.
     */
    private fun writeAndDismiss(block: suspend () -> Unit) {
        dismissDialog()
        write(block)
    }

    /**
     * Runs a settings write, turning any refusal into a sentence rather than a crash.
     *
     * "Fails" includes a value the repository *rejects*, not only a store it cannot write:
     * `setBarWeight` and `setDefaultRestDuration` both `require` a positive argument, and a
     * setting that will not save is the same event to a user however the write refused it.
     */
    private fun write(block: suspend () -> Unit) {
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

    /**
     * What the screen shows and what the stores do not know about.
     *
     * Three of the five deliberately do not come back. The wipe cannot still be running when the
     * process that was running it is gone; the message is about something the user was told
     * before they put the phone down; and the permission prompt is a request to Android that
     * went with the activity that raised it — re-raising it unasked on the way back in is the
     * one thing this screen is careful never to do (see [RequestNotificationPermissionUseCase]).
     *
     * [notificationsAllowed] is saved and immediately overwritten by the resume that follows a
     * restore, which is what makes it correct: the initial value is only what the screen draws
     * for the frame before Android is asked again.
     */
    @Serializable
    private data class ScreenState(
        val dialog: SettingsDialog? = null,
        @Transient
        val isDeleting: Boolean = false,
        @Transient
        val message: SettingsMessage? = null,
        val notificationsAllowed: Boolean = false,
        @Transient
        val notificationPermissionRequest: Boolean = false,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
