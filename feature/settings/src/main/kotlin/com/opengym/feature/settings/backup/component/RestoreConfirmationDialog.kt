package com.opengym.feature.settings.backup.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.DataFootprint
import com.opengym.feature.settings.R
import com.opengym.feature.settings.component.FootprintList
import com.opengym.feature.settings.component.TypedConfirmationDialog

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
 * ### What it does not say
 *
 * Which file was picked. The system picker showed it a moment ago and this dialog is about the
 * consequence, not the source; naming a document the app has not opened yet would be claiming
 * to have read it.
 *
 * @param footprint what is on the device now — the same numbers the screen's own card shows,
 *   which is what makes the dialog specific rather than a generic warning.
 * @param isRestoring the restore is running. The dialog stays up and inert until it is over.
 */
@Composable
fun RestoreConfirmationDialog(
    footprint: DataFootprint,
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
        FootprintList(
            footprint = footprint,
            settingsLine = stringResource(R.string.backup_restore_dialog_settings_line),
        )
    }
}
