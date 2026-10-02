package com.titan.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R

/**
 * A confirmation that cannot be given by accident: the destructive button stays inert until
 * the user has typed a word.
 *
 * ### Why typing, and not a second "Are you sure?"
 *
 * A confirm dialog is dismissed by muscle memory. The user who most needs stopping is the one
 * tapping through a screen they half-read, and a second button in the same place as the first
 * is a second tap they will make. Typing the word cannot be done by accident: it requires
 * reading, and reading is the whole intervention. #M4-23 allows either; this is the stronger
 * of the two and it costs one field.
 *
 * The word comes from resources, so it is translated. Matching is case- and
 * whitespace-insensitive: the point is that the user *typed the word*, and rejecting `delete`
 * for a missing shift key would be a puzzle rather than a safeguard.
 *
 * ### Two callers, one shell
 *
 * `DeleteAllDataDialog` and `RestoreConfirmationDialog` are the same intervention over the
 * same consequence — everything on the device goes — so they are the same dialog with
 * different words in it. They were one file's worth of duplication until the restore needed
 * it too; sharing it is what keeps the friction identical, which is the point of having a
 * house pattern for this at all rather than two dialogs that drift.
 *
 * @param title the question, drawn in the error role.
 * @param body the sentence above the list of what is at stake.
 * @param requiredWord what has to be typed. Shown in the prompt and matched against.
 * @param confirmLabel the destructive button's text when idle.
 * @param busyLabel its text while [isBusy]. The action is slow enough for a user to conclude
 *   nothing happened and press again, so it has to say so rather than merely go grey.
 * @param isBusy the action is running: the field and both buttons go inert, and the dialog
 *   cannot be dismissed. It stays up until the caller takes it down, because a dialog that
 *   vanished mid-wipe would leave the user watching a screen quietly emptying itself.
 * @param content what is at stake, drawn between [body] and the prompt — a [FootprintList],
 *   usually.
 */
@Composable
fun TypedConfirmationDialog(
    title: String,
    body: String,
    requiredWord: String,
    confirmLabel: String,
    busyLabel: String,
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Deliberately `remember` and not `rememberSaveable`: the confirmation must not survive
    // the dialog going away. A saved value would restore a dialog that comes back already
    // armed, which is exactly the accident the typing exists to prevent.
    var typed by remember { mutableStateOf("") }
    val isArmed = typed.trim().equals(requiredWord, ignoreCase = true)

    AlertDialog(
        modifier = modifier,
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            // Scrollable, because the list of what is at stake grows with the user's history
            // and the confirmation field must stay reachable however long it gets.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                content()

                Text(
                    text = stringResource(R.string.settings_delete_dialog_prompt, requiredWord),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy,
                    singleLine = true,
                    isError = typed.isNotBlank() && !isArmed,
                    keyboardOptions = KeyboardOptions(
                        // The word is shown in capitals, so the keyboard opens in capitals.
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                // Both conditions, not just the word: `isArmed` is what the user controls and
                // `!isBusy` is what stops a second run being started on top of the first.
                enabled = isArmed && !isBusy,
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(text = if (isBusy) busyLabel else confirmLabel)
            }
        },
        dismissButton = {
            TextButton(enabled = !isBusy, onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}
