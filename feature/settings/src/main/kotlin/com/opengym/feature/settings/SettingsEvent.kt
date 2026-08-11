package com.opengym.feature.settings

import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
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

    /**
     * The user typed the confirmation word and pressed the destructive button.
     *
     * The only event here that is not undoable by doing the opposite, and the only one the
     * screen may not send on a tap alone — see `DeleteAllDataDialog`.
     */
    data object DeleteAllConfirmed : SettingsEvent

    /** The snackbar has been shown; drop [SettingsUiState.message] so it cannot show twice. */
    data object MessageShown : SettingsEvent
}
