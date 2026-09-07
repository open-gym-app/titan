package com.opengym.feature.settings.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.DataFootprint
import com.opengym.feature.settings.R

/**
 * The confirmation for the one action in the app that destroys something and puts nothing
 * back.
 *
 * The typing, and why it is typing rather than a second button, is [TypedConfirmationDialog]'s
 * — this is that shell with the wipe's words in it and [FootprintList] for what is at stake.
 * `RestoreConfirmationDialog` is its twin over the same consequence, which is why the shell
 * and the list are shared rather than copied: a restore and a wipe destroy the same rows and
 * have to say so identically.
 *
 * @param isDeleting the wipe is running. See [TypedConfirmationDialog.isBusy].
 */
@Composable
fun DeleteAllDataDialog(
    footprint: DataFootprint,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TypedConfirmationDialog(
        title = stringResource(R.string.settings_delete_dialog_title),
        body = if (footprint.isEmpty) {
            stringResource(R.string.settings_delete_dialog_body_empty)
        } else {
            stringResource(R.string.settings_delete_dialog_body)
        },
        requiredWord = stringResource(R.string.settings_delete_dialog_word),
        confirmLabel = stringResource(R.string.settings_delete_dialog_confirm),
        busyLabel = stringResource(R.string.settings_delete_in_progress),
        isBusy = isDeleting,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        FootprintList(
            footprint = footprint,
            settingsLine = stringResource(R.string.settings_delete_dialog_settings_line),
        )
    }
}
