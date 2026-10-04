package com.titan.feature.settings.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.titan.feature.settings.R

/**
 * "Restore default templates?", naming what would come back.
 *
 * A plain confirmation rather than [TypedConfirmationDialog]: restoring only adds, and what it
 * adds the user can delete again from the template list. The names are in the body because
 * they are the whole of the decision — "adds 3 templates" asks the user to remember which
 * three they deleted, and "Legs, Push, Swim" does not.
 *
 * @param missing the names of the defaults that would be added, in the order the app defines
 *   them. The row that opens this is disabled when nothing is missing, but the dialog can still
 *   be up with an empty list: it is saved across process death and drawn before the first read
 *   of the template list arrives, and the defaults can come back some other way while it is
 *   open. It then says there is nothing to restore rather than "Adds 0 templates".
 */
@Composable
fun RestoreTemplatesDialog(
    missing: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = missing.joinToString(separator = stringResource(R.string.settings_restore_templates_separator))

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_restore_templates_dialog_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = if (missing.isEmpty()) {
                    stringResource(R.string.settings_restore_templates_none_missing)
                } else {
                    pluralStringResource(
                        R.plurals.settings_restore_templates_dialog_body,
                        missing.size,
                        missing.size,
                        names,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_restore_templates_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}
