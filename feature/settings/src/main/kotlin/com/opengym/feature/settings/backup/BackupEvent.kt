package com.opengym.feature.settings.backup

/**
 * Everything the backup screen can report.
 *
 * One event per thing that happened, as on the settings screen, so an unhandled case is a
 * compiler error in the ViewModel's `when` rather than a button that silently does nothing.
 */
sealed interface BackupEvent {

    /**
     * The export button was pressed.
     *
     * Carries [appVersion] because the screen is where it is known: it comes from `:app`'s
     * `BuildConfig` down through navigation, exactly as the About row's version does, and a
     * ViewModel in a feature module reading its own `BuildConfig` would stamp every backup
     * with the *library's* version. It is diagnostic only — nothing reads it back.
     */
    data class ExportRequested(val appVersion: String) : BackupEvent

    /**
     * The user picked a file from the system document picker.
     *
     * This does **not** start the restore. It opens the confirmation, because the restore
     * replaces everything on the device and the picker is one tap away from a file chosen by
     * accident.
     *
     * @property uri the document's location, as a string. A cancelled picker sends nothing at
     *   all, which is what leaves the database untouched.
     */
    data class RestoreFileChosen(val uri: String) : BackupEvent

    /** The confirmation was dismissed — Cancel, a tap outside, or back. Nothing was written. */
    data object RestoreDismissed : BackupEvent

    /**
     * The confirmation word was typed and the destructive button pressed.
     *
     * The only event here that is not undoable, and the only one the screen may not send on a
     * tap alone — `RestoreConfirmationDialog` is what holds the button inert until then.
     */
    data object RestoreConfirmed : BackupEvent

    /** The share sheet has been launched; drop the file so a rotation does not open a second one. */
    data object ShareHandled : BackupEvent

    /**
     * The user dismissed the outcome of the last export or restore.
     *
     * Both at once rather than one each: only one of them can be showing, since starting
     * either operation clears both.
     */
    data object OutcomeDismissed : BackupEvent
}
