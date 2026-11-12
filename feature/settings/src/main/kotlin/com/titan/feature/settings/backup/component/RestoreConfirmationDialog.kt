package com.titan.feature.settings.backup.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.DataFootprint
import com.titan.feature.settings.R
import com.titan.feature.settings.component.FootprintList
import com.titan.feature.settings.component.TypedConfirmationDialog

/**
 * The confirmation between picking a backup file and having it replace the device.
 *
 * ### Why this is the delete dialog again, and not a two-button confirm
 *
 * Because it is the same consequence. An import **replaces**: every row listed below is
 * deleted, and what comes back is whatever is in the file. A user who picked the wrong file —
 * an older backup, somebody else's, a half-written one — loses exactly what
 * `DeleteAllDataDialog` would have taken, with the difference that they were reaching for a
 * *safety* feature when they did it. Same consequence, same friction:
 * [TypedConfirmationDialog] is the shared shell and [FootprintList] the shared list of what is
 * at stake.
 *
 * The word is `RESTORE` rather than `DELETE`, because what the user is being asked to confirm
 * is the thing they think they are doing — a confirmation that made them type the wrong verb
 * would read as a bug, and the friction comes from having to *read* rather than from the word
 * being alarming.
 *
 * ### It names the file
 *
 * The name the file's provider gives, above what it will replace. The picker showed it a moment
 * ago, but this is the last thing between the user and a wipe, and "that is last year's backup"
 * is a mistake a name catches and a footprint does not. It is the provider's name, not the
 * file's contents — nothing has been read yet — so it says "File", not what is in it.
 *
 * @param footprint what is on the device now — the same numbers the screen's own card shows,
 *   which is what makes the dialog specific rather than a generic warning.
 * @param documentName what the provider calls the chosen file, already made safe to draw, or
 *   null while it has not said; the line is left out until then.
 * @param isRestoring the restore is running. The dialog stays up and inert until it is over.
 */
@Composable
fun RestoreConfirmationDialog(
    footprint: DataFootprint,
    documentName: String?,
    isRestoring: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TypedConfirmationDialog(
        title = stringResource(R.string.backup_restore_dialog_title),
        body = if (footprint.isEmpty) {
            stringResource(R.string.backup_restore_dialog_body_empty)
        } else {
            stringResource(R.string.backup_restore_dialog_body)
        },
        requiredWord = stringResource(R.string.backup_restore_dialog_word),
        confirmLabel = stringResource(R.string.backup_restore_dialog_confirm),
        busyLabel = stringResource(R.string.backup_restore_in_progress),
        isBusy = isRestoring,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        if (documentName != null) {
            Text(
                text = stringResource(R.string.backup_restore_dialog_file, documentName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        FootprintList(
            footprint = footprint,
            settingsLine = stringResource(R.string.backup_restore_dialog_settings_line),
        )
    }
}
