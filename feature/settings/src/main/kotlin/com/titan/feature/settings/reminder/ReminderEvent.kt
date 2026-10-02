package com.titan.feature.settings.reminder

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Everything the reminder screen can report.
 *
 * One event per control, in the shape `SettingsEvent` established: the screen says what
 * happened and the ViewModel decides what it means. The permission ones are the same three
 * steps of the same conversation settings has — they are here rather than shared because a
 * `sealed interface` cannot be implemented across module boundaries in a way that keeps each
 * screen's `when` exhaustive, and three small duplicated declarations are cheaper than one
 * shared event type that both screens have to handle every case of.
 */
sealed interface ReminderEvent {

    /** The master switch was flipped. */
    data class Toggled(val enabled: Boolean) : ReminderEvent

    /** One day's cell was tapped, with what it should become. */
    data class DaySelected(
        val day: DayOfWeek,
        val selected: Boolean,
    ) : ReminderEvent

    /** The time row was tapped. */
    data object TimeRequested : ReminderEvent

    /** The clock face was dismissed without a choice. */
    data object TimeDismissed : ReminderEvent

    /** A time was confirmed on the clock face. */
    data class TimeSelected(val time: LocalTime) : ReminderEvent

    /**
     * The user asked to be prompted for the permission, from the row that offers it.
     *
     * The row exists for the case the switch cannot reach: a user whose reminder is already on
     * has nothing left to enable, and so no gesture that would raise the prompt on their behalf.
     */
    data object PermissionRequested : ReminderEvent

    /** The prompt has been raised; drop [ReminderUiState.permissionRequest]. */
    data object PermissionRequestShown : ReminderEvent

    /**
     * Something happened that could have changed what the system allows: the prompt was
     * answered, or the screen came back from the system settings the blocked row leads to.
     *
     * Carries no value — the ViewModel asks the system itself, because the system is the only
     * thing that knows.
     */
    data object PermissionRechecked : ReminderEvent

    /** The snackbar has been shown; drop [ReminderUiState.saveFailed] so it cannot show twice. */
    data object MessageShown : ReminderEvent
}
