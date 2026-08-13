package com.opengym.feature.session.summary.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R

/**
 * "Save as template", and the one thing it needs asking: what to call it.
 *
 * The name is asked for rather than taken from the session, because the two are not the same
 * thing often enough to assume. A session is called "Push Day · Upper" *for the 28th of
 * April*; a template is called that forever, and sits in a list next to four others. Asking is
 * also what gives the duplicate-name refusal somewhere to be reported before anything has been
 * written — the field stays up with the name still in it, and the user retypes a word rather
 * than the whole thing.
 *
 * The body says what will be saved. "Save as template" on a screen full of figures is
 * genuinely ambiguous about whether the weights come with it, and they do.
 *
 * @param name what is in the field. Hoisted, like every other draft in this feature: the
 *   ViewModel decides when the dialog closes, which is when the write lands and not before.
 * @param isSaving the write is in flight. Both buttons and the field go inert rather than the
 *   dialog closing, so a refusal has somewhere to appear.
 */
@Composable
internal fun SaveAsTemplateDialog(
    name: String,
    isSaving: Boolean,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // A dialog that opens with the keyboard down asks for a second tap on the thing it just
    // opened to ask about.
    LaunchedEffect(Unit) { focus.requestFocus() }

    val canSave = name.isNotBlank() && !isSaving

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(text = stringResource(R.string.summary_template_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xxl)) {
                Text(
                    text = stringResource(R.string.summary_template_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    label = { Text(stringResource(R.string.summary_template_name)) },
                    shape = MaterialTheme.shapes.small,
                    enabled = !isSaving,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // The keyboard's own confirm does what the button does. A one-field dialog
                    // where "done" dismisses the keyboard and nothing else is one the user
                    // presses twice.
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onConfirm() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canSave) {
                Text(
                    text = stringResource(R.string.summary_template_save),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(
                    text = stringResource(R.string.summary_template_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}
