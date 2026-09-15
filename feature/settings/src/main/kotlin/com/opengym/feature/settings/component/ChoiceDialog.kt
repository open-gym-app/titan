package com.opengym.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.opengym.core.ui.theme.MIN_TOUCH_TARGET
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R
import com.opengym.core.ui.R as CoreR

/**
 * The picker behind most settings rows: a title, a list of options, pick one and it is saved.
 *
 * No confirm button. A radio dialog with Save asks the user to state the same intention twice
 * — the tap already said everything — and the only thing the second press buys is somewhere
 * for the choice to be silently dropped when they dismiss instead. Choosing closes the dialog
 * and writes; there is nothing to cancel because nothing is pending. Cancel is still offered
 * for leaving without choosing, which is a different intention and needs its own way out.
 *
 * The rows are a [selectableGroup] of [Role.RadioButton] items with the whole row selectable
 * rather than just the button, which is both the larger touch target and the thing that makes
 * a screen reader announce "Selected, Auto, radio button" instead of reading a label and an
 * unrelated control next to it. The state is said in words — the same two `SelectableOption`
 * uses — and every row is at least 48dp tall: a radio with no handler of its own reserves no
 * tap target, so the row has to.
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    choices: List<SettingsChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            // Scrollable: the rest-timer picker has nine options, which is taller than a
            // dialog on a small screen at a large font scale.
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                choices.forEach { choice ->
                    ChoiceRow(
                        choice = choice,
                        isSelected = choice.value == selected,
                        onSelect = { onSelect(choice.value) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}

/**
 * One option of a [ChoiceDialog]: a radio and its label, the whole row a single target.
 *
 * Internal rather than private so `TrainingGoalDialog` — a choice dialog with fields under
 * one of its choices — draws its options as this dialog draws them, rather than as a copy
 * that stops matching the first time the padding here changes.
 */
@Composable
internal fun <T> ChoiceRow(
    choice: SettingsChoice<T>,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val state = stringResource(if (isSelected) CoreR.string.option_selected else CoreR.string.option_not_selected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TOUCH_TARGET)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect)
            .semantics { stateDescription = state }
            .padding(vertical = OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        // `onClick = null`: the row above owns the click and the semantics. A button with its
        // own handler here would be a second, smaller target announcing the same thing twice.
        RadioButton(selected = isSelected, onClick = null)
        Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
            Text(
                text = choice.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (choice.detail != null) {
                Text(
                    text = choice.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
