package com.opengym.feature.settings

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.UserPreferences

/**
 * Everything the settings screen draws, as one value.
 *
 * The stored preferences are held as the domain's own [UserPreferences] rather than unpacked
 * into six fields: every row renders one of them, nothing here derives anything from them,
 * and a screen-shaped copy of a model the domain already owns is a second thing to keep in
 * step for no gain. Formatting them for display happens in the composable, in the user's
 * locale and unit system — see `:core:ui`'s `format` package.
 *
 * @param preferences the stored settings. Defaults are the real defaults, so a state built by
 *   hand in a test or a preview is a fresh install rather than a placeholder.
 * @param footprint how much of the user's own work is on the device, for the delete-all
 *   dialog. Zeroes until the first read arrives, which is also what an empty device reads.
 * @param dialog which picker is open, if any. One nullable field rather than six booleans:
 *   two dialogs cannot be open at once, and a state that can express it is a state somebody
 *   will eventually produce.
 * @param isDeleting a wipe is running. The dialog stays up and inert while it does — it is
 *   the one action here that takes long enough to need saying so, and dismissing it early
 *   would leave the user watching a settings screen that is quietly emptying itself.
 * @param message a one-shot outcome to show and forget. Present only between the event that
 *   caused it and the screen acknowledging it with [SettingsEvent.MessageShown].
 */
@Immutable
data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val footprint: DataFootprint = DataFootprint(),
    val dialog: SettingsDialog? = null,
    val isDeleting: Boolean = false,
    val message: SettingsMessage? = null,
)

/**
 * The picker a row opens.
 *
 * Named after the setting rather than after the kind of dialog, because two of them happen to
 * be a list of radio buttons today and that is not a fact worth encoding: the bar weight has
 * already outgrown it.
 */
enum class SettingsDialog {
    THEME,
    UNITS,
    REST_DURATION,
    BAR_WEIGHT,
    TRAINING_GOAL,
    ONE_REP_MAX_FORMULA,

    /** The destructive one. See `DeleteAllDataDialog` for why it is not a plain confirm. */
    DELETE_ALL,
}

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, so the ViewModel does not reach for a `Context` to build a
 * message and the screen keeps every user-visible word in its own resources. It is cleared by
 * [SettingsEvent.MessageShown] rather than by a timer, so a message cannot be shown twice by
 * a rotation that happens while the snackbar is up.
 */
enum class SettingsMessage {

    /** A preference write failed. The control has already snapped back to the stored value. */
    SAVE_FAILED,

    /** The wipe finished. Worth confirming: the screen it leaves behind looks much the same. */
    DELETE_SUCCEEDED,

    /** The wipe threw. Some stores may be empty and some may not — see `LocalDataRepository`. */
    DELETE_FAILED,
}
