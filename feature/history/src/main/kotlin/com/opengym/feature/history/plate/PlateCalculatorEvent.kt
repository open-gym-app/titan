package com.opengym.feature.history.plate

/**
 * Everything the plate calculator can report.
 *
 * Every weight here is **milligrams**, already converted. The screen is the part that knows
 * which unit system the user typed in — it is the one holding `LocalUnitSystem` — so the
 * conversion happens there and the ViewModel never has to ask. That is the settings screen's
 * rule (see `SettingsEvent.BarWeightSelected`) and the reason a stepper can be labelled `+2.5`
 * in one system and `+5` in the other without the ViewModel knowing either number.
 */
sealed interface PlateCalculatorEvent {

    /**
     * A stepper was pressed. [delta] is signed, in milligrams.
     *
     * A delta rather than the resulting weight, because the *step* is what the button means and
     * the current target is state the ViewModel already owns — a screen that computed the new
     * value would be reading a target that a preference write could have moved underneath it.
     */
    data class TargetStepped(val delta: Long) : PlateCalculatorEvent

    /** A weight was typed. Already parsed; the screen owns the unit system it was typed in. */
    data class TargetEntered(val milligrams: Long) : PlateCalculatorEvent

    /** The bar was changed, from a preset or a stepper. Written to the settings store. */
    data class BarWeightSelected(val milligrams: Long) : PlateCalculatorEvent

    /**
     * How many pairs of one size the user has. Zero is "none", which keeps the row in the
     * editor — see `PlateInventory.withPairs`.
     */
    data class PlatePairsSelected(
        val weight: Long,
        val pairs: Int,
    ) : PlateCalculatorEvent

    /**
     * "Use the standard set": forget the stored rack rather than overwrite it with a copy of
     * today's default, which would then stop following a change of unit system.
     */
    data object InventoryReset : PlateCalculatorEvent

    /** An editor was asked for. */
    data class SheetRequested(val sheet: PlateCalculatorSheet) : PlateCalculatorEvent

    /** The open editor was dismissed — a tap outside, a drag down, Cancel, or back. */
    data object SheetDismissed : PlateCalculatorEvent

    /** The snackbar has been shown; drop [PlateCalculatorUiState.message] so it cannot show twice. */
    data object MessageShown : PlateCalculatorEvent
}
