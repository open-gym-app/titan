package com.opengym.feature.history.template.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.feature.history.R

/**
 * "Leave without saving?"
 *
 * Raised only when there is something to lose — see `TemplateEditorUiState.isDirty` — because a
 * confirmation on a screen nobody changed is a tax on looking at a template.
 *
 * The editor holds a draft and writes nothing until SAVE, so backing out is genuinely free and
 * genuinely lossy at the same time: nothing in the store is at risk, and everything the user has
 * just typed is. That is what the question is about, and it is why the destructive answer is the
 * one that leaves.
 */
@Composable
internal fun DiscardTemplateEditsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        title = { Text(text = stringResource(R.string.template_editor_discard_title)) },
        text = {
            Text(
                text = stringResource(R.string.template_editor_discard_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.template_editor_discard_confirm),
                    // The one answer here that throws work away, and the only place this screen
                    // spends `error` as a label colour.
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.template_editor_discard_cancel))
            }
        },
    )
}
