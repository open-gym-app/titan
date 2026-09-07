package com.opengym.feature.settings.backup

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.model.DataFootprint

/**
 * Everything the backup screen draws, as one value.
 *
 * @param footprint what is on the device: the counts, when the first session was logged, and
 *   roughly how much space the database takes. It is the card at the top of the screen *and*
 *   the list inside the restore confirmation — the same numbers, because what a restore
 *   destroys is exactly what the card describes.
 * @param phase whether an export or a restore is running, and how far it has got. One field
 *   rather than two, because the two cannot run at once — starting one while the other is in
 *   flight would have them writing and wiping the same rows — and a state that can express it
 *   is a state somebody will eventually produce.
 * @param pendingRestoreUri the file the user picked, waiting on the confirmation dialog. Held
 *   as a `String` rather than a `Uri` so the state carries no platform type: a preview and a
 *   test build one by hand, and the screen is the only thing that needs it back as a `Uri`.
 * @param lastExport the outcome of the most recent export, or null before there has been one.
 *   Persistent rather than a one-shot snackbar: it names a file the user may want to find
 *   again, and a message about a file that has scrolled past is no message at all.
 * @param lastRestore the outcome of the most recent restore. Persistent for a stronger
 *   reason — a failed restore has to leave a sentence on the screen saying *which* failure it
 *   was and that the data is untouched, and four seconds of snackbar is not that.
 * @param lastSave how the last "save a copy" ended, or null if the user has not asked for one.
 *   Separate from [lastExport] rather than folded into it because the two are separate facts
 *   about separate files — the export succeeded and its card must keep saying so, and offering
 *   the buttons that copy and share it, whether or not a copy to a chosen folder then failed.
 */
@Immutable
data class BackupUiState(
    val footprint: DataFootprint = DataFootprint(),
    val phase: BackupPhase = BackupPhase.Idle,
    val pendingRestoreUri: String? = null,
    val lastExport: ExportOutcome? = null,
    val lastRestore: RestoreOutcome? = null,
    val lastSave: SaveOutcome? = null,
) {

    /** Whether anything is running. Both buttons and the picker go inert while something is. */
    val isBusy: Boolean get() = phase != BackupPhase.Idle
}

/**
 * How far the running operation has got.
 *
 * Determinate throughout, which is the point: both use cases count their rows before they
 * write any, so a 50,000-set history draws a bar that fills evenly rather than a spinner the
 * user cannot tell from a hang. The one indeterminate step is [Validating], and it is over in
 * well under a second — the row count it would divide by is not known until the parse it is
 * reporting on has finished.
 */
sealed interface BackupPhase {

    /** Nothing is running. */
    data object Idle : BackupPhase

    /** Rows are being read and written into the file. */
    data class Exporting(
        val rowsWritten: Long,
        val rowsTotal: Long,
    ) : BackupPhase

    /** The chosen document is being parsed and checked. Nothing has been written yet. */
    data object Validating : BackupPhase

    /** The document is being written into the database, inside the transaction that wiped it. */
    data class Importing(
        val rowsWritten: Long,
        val rowsTotal: Long,
    ) : BackupPhase

    /**
     * The finished export is being copied to the place the user picked.
     *
     * The one phase with no row count, and it does not want one: this is a byte-for-byte copy
     * of a file that already exists, so there is nothing to count that the user would
     * recognise. It is a phase at all — rather than a copy that happens silently — because a
     * 30 MB write to an SD card or a cloud provider is not instant, and because [isBusy] is
     * what stops a second export from overwriting the file being read.
     */
    data object Saving : BackupPhase
}

/** How the last export ended. */
sealed interface ExportOutcome {

    /**
     * @property fileName what the file is called, for a sentence naming it. The full path is
     *   not shown: it is an app-private directory the user cannot browse to, and quoting it
     *   would invite them to try.
     * @property rowsWritten how many rows went into it.
     */
    data class Succeeded(
        val fileName: String,
        val rowsWritten: Long,
    ) : ExportOutcome

    /**
     * The file could not be written — no space, a storage volume that went away mid-write.
     *
     * Carries no detail, unlike [RestoreOutcome.Failed]: there is one way for this to fail and
     * one thing to do about it, where an import has four distinguishable causes that lead the
     * user somewhere different. The partial file is deleted before this is reported.
     */
    data object Failed : ExportOutcome
}

/**
 * How the last "save a copy" ended.
 *
 * Two cases with nothing in them, for the reason [ExportOutcome.Failed] carries nothing: the
 * user chose the destination a moment ago, so naming it back at them says nothing they do not
 * know, and every way a write to it can fail leads to the same "pick somewhere else".
 */
sealed interface SaveOutcome {

    /** The bytes are at the location the user picked. */
    data object Succeeded : SaveOutcome

    /** The destination could not be written — no space, a provider that went away, a revoked grant. */
    data object Failed : SaveOutcome
}

/** How the last restore ended. */
sealed interface RestoreOutcome {

    /** @property rowsRestored every row the document held, now in the database. */
    data class Succeeded(val rowsRestored: Long) : RestoreOutcome

    /**
     * @property error which of the four refusals it was. Kept as the domain's own type rather
     *   than flattened into a message enum, because two of them carry values a user needs —
     *   [ImportError.UnsupportedSchemaVersion] names both versions — and because a `when` over
     *   a sealed type is what makes "each case renders its own message" a compiler-checked
     *   claim rather than a hope. See `BackupLabels.kt`.
     */
    data class Failed(val error: ImportError) : RestoreOutcome
}
