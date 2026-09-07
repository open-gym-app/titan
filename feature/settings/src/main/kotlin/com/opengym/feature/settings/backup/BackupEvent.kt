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

    /**
     * The weekly automatic backup was switched on or off.
     *
     * Carries the value the switch moved *to* rather than being a "toggle" with no argument:
     * the switch is drawn from the stored flag, so an event meaning "flip whatever is there"
     * would invert a value that has already changed underneath it — a second tap landing while
     * the first write is still in flight.
     */
    data class AutoBackupToggled(val enabled: Boolean) : BackupEvent

    /**
     * The user picked where a copy of the last export should go.
     *
     * The counterpart of [RestoreFileChosen], and it arrives the same way — from a system
     * picker the route launched, as a string, with a cancelled picker sending nothing at all.
     * Unlike a restore it needs no confirmation: writing a file the user just named, at a
     * location they just chose, destroys nothing.
     *
     * @property uri where to write, as a string. It is a document the picker has already
     *   created, so the copy truncates it rather than appending to it.
     */
    data class SaveDestinationChosen(val uri: String) : BackupEvent

    /**
     * The user dismissed the outcome of the last export, copy or restore.
     *
     * All three at once rather than one each: an export and a restore cannot both be showing,
     * since starting either clears both, and a copy's outcome is a footnote to the export's
     * that has nothing left to say once the card naming the file is gone.
     */
    data object OutcomeDismissed : BackupEvent
}
