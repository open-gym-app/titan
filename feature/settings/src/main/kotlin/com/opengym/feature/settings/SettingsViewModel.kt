package com.opengym.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationPermission
import com.opengym.core.domain.notification.NotificationPermissionState
import com.opengym.core.domain.repository.LocalDataRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 *
 * ### The notification permission is asked for late, and once
 *
 * Nothing here requests `POST_NOTIFICATIONS` at startup. The prompt goes up the first time the
 * user turns a notification on — a prompt with no context attached is the one most reliably
 * denied, and a denial cannot be taken back — and it can only go up once, because Android
 * shows it once. [maybeRequestNotificationPermission] holds both halves of that: it reads the
 * *store* rather than the last-published state for whether the prompt has been raised before,
 * so the decision does not depend on a collector having caught up, and it records the raising
 * before the answer arrives, because a user who dismisses the prompt has still been asked.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val localDataRepository: LocalDataRepository,
    private val notificationPermission: NotificationPermission,
) : ViewModel() {

    /** What the screen owns, as opposed to what the stores do. */
    private val screenState = MutableStateFlow(
        // Read once at construction and again on every resume. There is no callback for this
        // changing, and it changes in a place the app cannot see.
        ScreenState(notificationsAllowed = notificationPermission.areNotificationsAllowed()),
    )

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

    private fun setTrainingGoal(goal: TrainingGoal) =
        writeAndDismiss { userPreferencesRepository.setTrainingGoal(goal) }

    private fun setOneRepMaxFormula(formula: OneRepMaxFormula) =
        writeAndDismiss { userPreferencesRepository.setOneRepMaxFormula(formula) }

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
     * It would not on Android 12 and below (there is no runtime permission), nor once the
     * prompt has been raised before (Android shows it once and then answers for the user), nor
     * when notifications are already allowed. The first two leave the state
     * [NotificationPermissionState.DENIED], where the settings group offers the row into the
     * system settings instead — the only thing that can still change the answer; the third is
     * a state with nothing to say.
     *
     * The "we have asked" flag is written *here*, before the answer, because the prompt going
     * up is the event it records — a user who swipes it away has been asked, and asking again
     * on their next tap is how an app teaches somebody to refuse.
     */
    private suspend fun maybeRequestNotificationPermission() {
        val alreadyRequested = userPreferencesRepository.observe().first().notifications.permissionRequested
        val state = NotificationPermissionState.of(
            allowed = notificationPermission.areNotificationsAllowed(),
            runtimePermissionRequired = notificationPermission.isRuntimePermissionRequired,
            alreadyRequested = alreadyRequested,
        )
        if (state != NotificationPermissionState.NOT_REQUESTED) return

        userPreferencesRepository.setNotificationPermissionRequested()
        screenState.update { it.copy(notificationPermissionRequest = true) }
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

    /** The half of the state that belongs to the screen rather than to a store. */
    private data class ScreenState(
        val dialog: SettingsDialog? = null,
        val isDeleting: Boolean = false,
        val message: SettingsMessage? = null,
        val notificationsAllowed: Boolean = false,
        val notificationPermissionRequest: Boolean = false,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
