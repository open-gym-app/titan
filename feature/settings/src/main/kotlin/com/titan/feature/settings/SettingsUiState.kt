package com.titan.feature.settings

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationPermissionState

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
 * @param notificationPermission whether the system will carry what the notification switches
 *   ask for. Not a preference and not stored: it is re-read from the system every time the
 *   screen resumes, because it changes in system settings with the app not running. A switch
 *   that is on while this is [NotificationPermissionState.DENIED] is a switch that does
 *   nothing, and the group says so rather than looking healthy.
 * @param missingDefaultTemplates the names of the default templates the user has deleted, in
 *   the order the app defines them — what the restore row would put back. Empty when every
 *   default is in the template list, and until the first read arrives: the row then reads as
 *   having nothing to restore, which is the common case and the one a fresh install is in.
 * @param notificationPermissionRequest a one-shot: raise the system permission prompt. The
 *   ViewModel decides *when* to ask — the first time a notification is turned on — and the
 *   screen is the only thing that can actually ask, so the decision travels as state and is
 *   cleared by [SettingsEvent.Notification.PermissionRequestShown].
 */
@Immutable
data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val footprint: DataFootprint = DataFootprint(),
    val dialog: SettingsDialog? = null,
    val isDeleting: Boolean = false,
    val message: SettingsMessage? = null,
    val missingDefaultTemplates: List<String> = emptyList(),
    val notificationPermission: NotificationPermissionState = NotificationPermissionState.GRANTED,
    val notificationPermissionRequest: Boolean = false,
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
    FITNESS_LEVEL,
    ONE_REP_MAX_FORMULA,

    /** Putting back the default templates the user deleted — see `RestoreTemplatesDialog`. */
    RESTORE_TEMPLATES,

    /** The destructive one. See `DeleteAllDataDialog` for why it is not a plain confirm. */
    DELETE_ALL,
}

/**
 * Something that happened and needs saying once.
 *
 * A sealed type rather than a string, so the ViewModel does not reach for a `Context` to build
 * a message and the screen keeps every user-visible word in its own resources. A sealed
 * interface rather than an enum because one of them carries a count. It is cleared by
 * [SettingsEvent.MessageShown] rather than by a timer, so a message cannot be shown twice by a
 * rotation that happens while the snackbar is up.
 */
sealed interface SettingsMessage {

    /** A preference write failed. The control has already snapped back to the stored value. */
    data object SaveFailed : SettingsMessage

    /** The wipe finished. Worth confirming: the screen it leaves behind looks much the same. */
    data object DeleteSucceeded : SettingsMessage

    /** The wipe threw. Some stores may be empty and some may not — see `LocalDataRepository`. */
    data object DeleteFailed : SettingsMessage

    /**
     * [count] default templates are back in the template list. Said, because the list is on
     * another screen and the row that did it only changes its supporting line. Zero when they
     * had all come back some other way while the dialog was open.
     */
    data class TemplatesRestored(val count: Int) : SettingsMessage

    /**
     * Restoring threw, a default was refused, or one names an exercise the catalogue lacks.
     * The ones added before it stay added.
     */
    data object RestoreFailed : SettingsMessage
}
