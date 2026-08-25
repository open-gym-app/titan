package com.opengym.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R
import java.text.NumberFormat

/**
 * The one dialog in the app that destroys something, and the only one that asks the user to
 * type.
 *
 * ### Why typing, and not a second "Are you sure?"
 *
 * A confirm dialog is dismissed by muscle memory. The user who most needs stopping here is
 * the one tapping through a screen they half-read, and a second button in the same place as
 * the first is a second tap they will make. Typing the word cannot be done by accident: it
 * requires reading, and reading is the whole intervention. #M4-23 allows either; this is the
 * stronger of the two and it costs one field.
 *
 * The word itself comes from resources, so it is translated. Matching is case- and
 * whitespace-insensitive: the point is that the user *typed the word*, and rejecting `delete`
 * for a missing shift key would be a puzzle rather than a safeguard.
 *
 * ### What it says
 *
 * The counts, per kind, from [DataFootprint] — not "all your data", which nobody weighs. Only
 * non-zero lines are listed, so the dialog is as long as the user's history is real, and the
 * settings line is always there because settings are always reset. A device with nothing
 * logged gets a shorter sentence rather than a list of zeroes.
 *
 * @param isDeleting the wipe is running: the field and both buttons go inert and the confirm
 *   button says so, because this is the one action here slow enough for a user to conclude
 *   nothing happened and press it again.
 */
@Composable
fun DeleteAllDataDialog(
    footprint: DataFootprint,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requiredWord = stringResource(R.string.settings_delete_dialog_word)

    // Deliberately `remember` and not `rememberSaveable`: the confirmation must not survive
    // the dialog going away. A saved value would restore a dialog that comes back already
    // armed, which is exactly the accident the typing exists to prevent.
    var typed by remember { mutableStateOf("") }
    val isArmed = typed.trim().equals(requiredWord, ignoreCase = true)

    AlertDialog(
        modifier = modifier,
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.settings_delete_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            // Scrollable, because the list of what is lost grows with the user's history and
            // the confirmation field must stay reachable however long it gets.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
            ) {
                Text(
                    text = if (footprint.isEmpty) {
                        stringResource(R.string.settings_delete_dialog_body_empty)
                    } else {
                        stringResource(R.string.settings_delete_dialog_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FootprintList(footprint)

                Text(
                    text = stringResource(R.string.settings_delete_dialog_prompt, requiredWord),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDeleting,
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
                // `!isDeleting` is what stops a second wipe being started on top of the first.
                enabled = isArmed && !isDeleting,
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(
                    text = if (isDeleting) {
                        stringResource(R.string.settings_delete_in_progress)
                    } else {
                        stringResource(R.string.settings_delete_dialog_confirm)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !isDeleting, onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}

/**
 * What is about to be lost, one line per kind, counts right-aligned in the mono numeric role
 * so they form a column the eye can total.
 */
@Composable
private fun FootprintList(footprint: DataFootprint) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
        FootprintLine(footprint.sessions, R.plurals.settings_count_sessions)
        FootprintLine(footprint.sets, R.plurals.settings_count_sets)
        FootprintLine(footprint.templates, R.plurals.settings_count_templates)
        FootprintLine(footprint.personalRecords, R.plurals.settings_count_personal_records)
        FootprintLine(footprint.customExercises, R.plurals.settings_count_custom_exercises)
        FootprintLine(footprint.bodyWeightLogs, R.plurals.settings_count_body_weight_logs)

        // Always shown, and without a count: settings are reset whatever else is here, and a
        // wipe that quietly also cleared them would be a surprise on the next launch.
        Text(
            text = stringResource(R.string.settings_delete_dialog_settings_line),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One `184  sessions` line. Renders nothing at all when the count is zero. */
@Composable
private fun FootprintLine(
    count: Int,
    pluralId: Int,
) {
    if (count == 0) return

    Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
        Text(
            // The composition's locale rather than the process default, so the grouping
            // separator follows a locale change without the dialog having to be rebuilt.
            text = NumberFormat.getIntegerInstance(Locale.current.platformLocale).format(count),
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(COUNT_COLUMN_WIDTH),
        )
        Text(
            text = pluralStringResource(pluralId, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Wide enough for five digits and a group separator — `12,345` — which is more sets than any
 * plausible history and keeps the labels aligned whatever the numbers are.
 */
private val COUNT_COLUMN_WIDTH = 64.dp
