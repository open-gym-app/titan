package com.titan.feature.settings.reminder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.notification.NotificationPermissionState
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.time.Clock
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
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The reminder screen's state, the writes behind it, and the alarms behind those.
 *
 * ### Every write is followed by a sync, and that is the whole feature
 *
 * A stored schedule that no alarm is pointed at is a setting that does nothing, and it is the
 * one way this feature fails silently: the screen reads back correctly, the switch stays on,
 * and nothing ever fires. So [write] does both — it stores, then calls
 * [ReminderScheduler.sync] — rather than leaving the second half to each caller, where the
 * fourth one added would be the one that forgets.
 *
 * The order matters. `sync` re-reads the store rather than being handed anything, so it must
 * run *after* the write it is reacting to; running it first would arm the schedule that was
 * just replaced. See [ReminderScheduler], which says why it takes no arguments.
 *
 * ### The clock is read at compose time, once per state
 *
 * "Next: Monday, in 2 days" is a function of now, and the screen must not compute it — a
 * composable that read the clock would recompose to a different answer on every frame and
 * screenshot differently on every run. It is computed here, from the injected [Clock], and
 * refreshed whenever the preferences change. It goes stale if the screen is left open across
 * midnight, which is a settings screen nobody is looking at.
 */
@HiltViewModel
class ReminderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val reminderScheduler: ReminderScheduler,
    private val notificationPermission: NotificationPermission,
    private val requestNotificationPermission: RequestNotificationPermissionUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val screenState = savedStateHandle.savedScreenState(viewModelScope) {
        // Read at construction and again on every resume: there is no callback for either of
        // these changing, and both change in places the app cannot see.
        ScreenState(
            notificationsAllowed = notificationPermission.areNotificationsAllowed(),
            isExact = reminderScheduler.isExact(),
        )
    }

    val uiState: StateFlow<ReminderUiState> = combine(
        userPreferencesRepository.observe(),
        screenState,
    ) { preferences, screen ->
        val enabled = NotificationKind.REMINDERS in preferences.notifications
        ReminderUiState(
            enabled = enabled,
            reminders = preferences.reminders,
            // Null unless a reminder would actually fire. The screen has a different line for
            // each reason there is none, and a date drawn beside "reminders are off" would be
            // the screen contradicting itself.
            nextOccurrence = when {
                enabled -> preferences.reminders.nextOccurrenceOrNull(clock.nowMillis(), ZoneId.systemDefault())
                else -> null
            },
            permission = NotificationPermissionState.of(
                allowed = screen.notificationsAllowed,
                runtimePermissionRequired = notificationPermission.isRuntimePermissionRequired,
                alreadyRequested = preferences.notifications.permissionRequested,
            ),
            isExact = screen.isExact,
            permissionRequest = screen.permissionRequest,
            timePickerOpen = screen.timePickerOpen,
            saveFailed = screen.saveFailed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        // Carrying the restored time picker, for the reason the settings screen carries its
        // dialog: the store read is a DataStore one, and a picker that flashes shut has been lost
        // as far as anyone looking at it is concerned.
        initialValue = ReminderUiState(timePickerOpen = screenState.value.timePickerOpen),
    )

    fun onEvent(event: ReminderEvent) {
        when (event) {
            is ReminderEvent.Toggled -> setEnabled(event.enabled)
            is ReminderEvent.DaySelected -> setDay(event.day, event.selected)
            ReminderEvent.TimeRequested -> screenState.update { it.copy(timePickerOpen = true) }
            ReminderEvent.TimeDismissed -> screenState.update { it.copy(timePickerOpen = false) }
            is ReminderEvent.TimeSelected -> setTime(event.time)
            ReminderEvent.PermissionRequested -> write { maybePrompt() }
            ReminderEvent.PermissionRequestShown -> screenState.update { it.copy(permissionRequest = false) }
            ReminderEvent.PermissionRechecked -> recheck()
            ReminderEvent.MessageShown -> screenState.update { it.copy(saveFailed = false) }
        }
    }

    /**
     * Stores the switch, and asks for the permission behind it if this is it going on.
     *
     * The write is unconditional and comes first: the preference is what the user *wants*, and
     * it is recorded whether or not the permission is ever granted. Turning it off asks
     * nothing — there is nothing left to deliver — which is the other half of the rule that the
     * prompt only ever follows an act of enabling.
     */
    private fun setEnabled(enabled: Boolean) =
        write {
            userPreferencesRepository.setNotificationEnabled(NotificationKind.REMINDERS, enabled)
            if (enabled) maybePrompt()
        }

    /**
     * Stores one day's cell.
     *
     * The set is never read here and then written back: `setReminderDay` does that inside the
     * store's transaction, which is what makes two cells tapped in quick succession two changes
     * rather than one overwriting the other. See the repository.
     */
    private fun setDay(
        day: DayOfWeek,
        selected: Boolean,
    ) = write { userPreferencesRepository.setReminderDay(day, selected) }

    /**
     * Stores the chosen time, with the clock face closed first.
     *
     * In that order and both unconditionally, matching `SettingsViewModel.writeAndDismiss`: the
     * dialog is closed by *making a choice*, not by the choice landing on disk, so a slow write
     * does not leave a clock face up looking unresponsive.
     */
    private fun setTime(time: LocalTime) {
        screenState.update { it.copy(timePickerOpen = false) }
        write { userPreferencesRepository.setReminderTime(time) }
    }

    /** Asks the screen to raise the system prompt, if raising it would show the user anything. */
    private suspend fun maybePrompt() {
        if (requestNotificationPermission()) {
            screenState.update { it.copy(permissionRequest = true) }
        }
    }

    /**
     * Re-reads both of the answers only the system has.
     *
     * The exact-alarm permission is re-read alongside the notification one because it is
     * revoked in the same place and by the same gesture — a trip through system settings — and
     * a screen that came back saying "delivery may be delayed" after the user has just fixed it
     * is worse than one that never said it.
     */
    private fun recheck() =
        screenState.update {
            it.copy(
                notificationsAllowed = notificationPermission.areNotificationsAllowed(),
                isExact = reminderScheduler.isExact(),
            )
        }

    /**
     * Runs a settings write and re-points the alarms at what it left behind.
     *
     * `sync` runs after every one of them, including the permission row's event, which writes
     * nothing this could react to. That is one branch fewer rather than a claim about the
     * permission: `sync` reads the store itself and is idempotent, so a pass that changes
     * nothing costs seven pending intents and buys a rule with no exceptions in it — every
     * write is followed by a sync, and there is no fourth caller to get it wrong.
     *
     * Catches [Throwable] for the reason `SettingsViewModel.write` gives: the store is not the
     * only thing that can refuse, `setReminderTime` validates its argument, and the ways a
     * settings screen actually breaks should reach the user as a sentence rather than a crash.
     * Cancellation is re-thrown, as it must be — a cancelled `viewModelScope` is the screen
     * going away, not a write that failed.
     */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                reminderScheduler.sync()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update { it.copy(saveFailed = true) }
            }
        }
    }

    /**
     * What the screen shows and what the store does not know about.
     *
     * Only the open time picker survives a process death, and that is the point of saving this
     * at all (#183): the other four are answers Android gives, and Android is asked again on the
     * resume that follows a restore. A permission prompt in particular must not come back — it
     * was a request made by an activity that no longer exists, and raising it again unasked is
     * the second prompt the platform answers with the refusal from the first.
     */
    @Serializable
    private data class ScreenState(
        @Transient
        val notificationsAllowed: Boolean = false,
        @Transient
        val isExact: Boolean = true,
        @Transient
        val permissionRequest: Boolean = false,
        val timePickerOpen: Boolean = false,
        @Transient
        val saveFailed: Boolean = false,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
