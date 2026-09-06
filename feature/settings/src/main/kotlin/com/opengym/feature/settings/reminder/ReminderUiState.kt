package com.opengym.feature.settings.reminder

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.notification.NotificationPermissionState
import com.opengym.core.domain.notification.ReminderPreferences

/**
 * Everything the reminder screen draws, as one value.
 *
 * @param enabled whether the user wants workout reminders at all. This is
 *   [com.opengym.core.domain.notification.NotificationKind.REMINDERS] in the stored
 *   notification preferences, not a second flag — see [ReminderPreferences] for why the
 *   schedule and the switch are kept in different places.
 * @param reminders which days and what time. Held as the domain's own value rather than
 *   unpacked, matching `SettingsUiState`: every control renders one of its two fields and
 *   nothing here derives anything from them.
 * @param nextOccurrence when the next reminder is due, as epoch millis UTC, or null when there
 *   is none — the switch is off, or no day is chosen. Computed in the ViewModel rather than in
 *   the screen because it is a function of the clock, and a composable that read the clock
 *   would be a composable whose screenshot changes every time it is taken.
 * @param permission whether the system will carry what the switch asks for. Re-read on every
 *   resume, for the reason `SettingsUiState` gives: it changes in system settings with the app
 *   not running.
 * @param isExact whether this device lets the app fire the alarm on the minute. False leaves
 *   the reminder scheduled and possibly late, which the screen says out loud — a nudge that
 *   arrives at 18:11 with no explanation reads as a broken app.
 * @param permissionRequest a one-shot: raise the system permission prompt. The ViewModel
 *   decides when, the screen is the only thing that can actually ask, so the decision travels
 *   as state and is cleared by [ReminderEvent.PermissionRequestShown].
 * @param timePickerOpen whether the clock face is up.
 * @param saveFailed a write was refused and the controls have snapped back to what is stored.
 *   Shown once and cleared by [ReminderEvent.MessageShown].
 */
@Immutable
data class ReminderUiState(
    val enabled: Boolean = false,
    val reminders: ReminderPreferences = ReminderPreferences(),
    val nextOccurrence: Long? = null,
    val permission: NotificationPermissionState = NotificationPermissionState.GRANTED,
    val isExact: Boolean = true,
    val permissionRequest: Boolean = false,
    val timePickerOpen: Boolean = false,
    val saveFailed: Boolean = false,
) {

    /**
     * Whether the screen has a next reminder to name.
     *
     * Three things have to be true and the screen draws a different line for each way they are
     * not — the switch, at least one day, and the permission. This is the one that means "there
     * is a date to put on the header"; the others are said in the sentence under it.
     */
    val hasNextOccurrence: Boolean get() = nextOccurrence != null
}
