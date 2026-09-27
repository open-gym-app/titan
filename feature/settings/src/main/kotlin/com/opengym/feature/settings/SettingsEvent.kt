package com.opengym.feature.settings

import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.notification.NotificationKind
import kotlin.time.Duration

/**
 * Everything the settings screen can report.
 *
 * Spelled out one event per row rather than as a generic `RowClicked(row)` carrying an enum:
 * the point of the split is that the screen says *what happened* and the ViewModel decides
 * what it means, and "the theme row was tapped" is a fact where "row number three was tapped"
 * is a lookup waiting to go wrong. It also makes an unhandled case a compiler error in the
 * `when` rather than a row that silently does nothing.
 */
sealed interface SettingsEvent {

    /** A row that opens a picker was tapped. */
    data class DialogRequested(val dialog: SettingsDialog) : SettingsEvent

    /** The open picker was dismissed without a choice — a tap outside, Cancel, or back. */
    data object DialogDismissed : SettingsEvent

    data class ThemeSelected(val theme: ThemePreference) : SettingsEvent

    data class UnitSystemSelected(val unitSystem: UnitSystem) : SettingsEvent

    data class RestDurationSelected(val duration: Duration) : SettingsEvent

    /**
     * A bar weight, already converted to the milligrams everything is stored in — the screen
     * owns the unit system it was typed in, so the conversion happens where that is known.
     */
    data class BarWeightSelected(val milligrams: Long) : SettingsEvent

    data class TrainingGoalSelected(val goal: TrainingGoal) : SettingsEvent

    data class FitnessLevelSelected(val level: FitnessLevel) : SettingsEvent

    data class OneRepMaxFormulaSelected(val formula: OneRepMaxFormula) : SettingsEvent

    /**
     * The user typed the confirmation word and pressed the destructive button.
     *
     * The only event here that is not undoable by doing the opposite, and the only one the
     * screen may not send on a tap alone — see `DeleteAllDataDialog`.
     */
    data object DeleteAllConfirmed : SettingsEvent

    /**
     * The notifications group's events, gathered under one name.
     *
     * The only group here, and it earns the nesting: three of the four are not a setting being
     * changed at all but steps in one conversation with the system permission, and the
     * ViewModel handles them as a unit — see `SettingsViewModel.onNotificationEvent`. Keeping
     * them flat alongside `ThemeSelected` would put a `when` in front of the reader that mixes
     * "the user picked a value" with "the platform prompt has been raised".
     */
    sealed interface Notification : SettingsEvent {

        /** A notification switch was flipped. */
        data class Toggled(
            val kind: NotificationKind,
            val enabled: Boolean,
        ) : Notification

        /**
         * The user asked to be prompted for the permission, from the row that offers it.
         *
         * The row exists for the case the switches cannot reach: the rest timer is on by
         * default, so a fresh install has a notification-backed setting enabled that the user
         * never *enabled* — and the permission is never asked for on their behalf. This is how
         * they ask for it without having to switch something off and on again.
         */
        data object PermissionRequested : Notification

        /** The prompt has been raised; drop [SettingsUiState.notificationPermissionRequest]. */
        data object PermissionRequestShown : Notification

        /**
         * Something happened that could have changed the permission: the prompt was answered,
         * or the screen came back from the system settings the blocked row leads to.
         *
         * Reported by the screen rather than polled, and it carries no value — the ViewModel
         * asks the system itself, because the system is the only thing that knows.
         */
        data object PermissionRechecked : Notification
    }

    /** The snackbar has been shown; drop [SettingsUiState.message] so it cannot show twice. */
    data object MessageShown : SettingsEvent
}
