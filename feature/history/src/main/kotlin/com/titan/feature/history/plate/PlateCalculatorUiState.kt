package com.titan.feature.history.plate

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.plate.PlateCalculator
import com.titan.core.domain.plate.PlateSolution

/**
 * Everything the plate calculator draws.
 *
 * ### The loading is derived, not stored
 *
 * [solution] is computed here from the two things that decide it, so there is no path where the
 * screen shows a barbell that belongs to a different target — which is the one bug this screen
 * could have that a user would act on. The search is a few hundred steps over an array (see
 * [PlateCalculator]) and runs once per state, not once per frame: a `data class` property with
 * an initialiser is evaluated at construction.
 *
 * It also means a preview or a test says *what the user asked for* and gets the real answer,
 * rather than hand-writing a loading that agrees with nothing.
 *
 * @param target the weight the user wants on the bar, in milligrams.
 * @param preferences the bar and the rack, from the store. Defaults are the real defaults, so a
 *   state built by hand is a fresh install rather than a placeholder.
 * @param sheet which editor is open, if any.
 * @param message a one-shot outcome to show and forget — the settings screen's pattern, and for
 *   the same reason: the only thing that can fail here is a preference write.
 */
@Immutable
data class PlateCalculatorUiState(
    val target: Long = UserPreferences.DEFAULT_BAR_WEIGHT,
    val preferences: UserPreferences = UserPreferences(),
    val sheet: PlateCalculatorSheet? = null,
    val message: PlateCalculatorMessage? = null,
) {
    /** What goes on the bar, and how close that gets. See the note above. */
    val solution: PlateSolution = PlateCalculator.solve(
        target = target,
        barWeight = preferences.barWeight,
        inventory = preferences.plates,
    )

    /**
     * Every size the rack knows, heaviest first — what the diagram and the legend colour by.
     *
     * The whole rack rather than only the sizes in use, so a plate keeps its colour and its
     * height as the target moves under it. See `BarbellDiagram`.
     */
    val denominations: List<Long> get() = preferences.plates.stock.map { it.weight }
}

/** The one editor this screen opens. A nullable field rather than a boolean per sheet. */
enum class PlateCalculatorSheet {

    /** The bar and the rack — the two stored things the answer depends on. */
    EQUIPMENT,

    /** The target, typed rather than stepped to. */
    TARGET,
}

/**
 * Something that happened and needs saying once.
 *
 * One value, because there is one thing on this screen that can fail: the bar weight and the
 * rack are written to the settings store, and a store that cannot be written leaves a control
 * that moved and did not save. Everything else here is arithmetic.
 */
enum class PlateCalculatorMessage {

    /** A write to the settings store threw. The control has snapped back to what is stored. */
    SAVE_FAILED,
}
