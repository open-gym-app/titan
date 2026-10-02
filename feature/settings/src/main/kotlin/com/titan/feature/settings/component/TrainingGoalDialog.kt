package com.titan.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.ui.component.detail
import com.titan.core.ui.component.label
import com.titan.core.ui.format.formatWeightValue
import com.titan.core.ui.format.parseWeightMilligrams
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R

/**
 * The training goal, and — under CUSTOM — the rep range and weight step it runs on.
 *
 * A [ChoiceDialog] with a Save button, which the plain one deliberately lacks: three of the
 * four choices are complete the moment they are tapped, but the fourth asks two more
 * questions, and a dialog that saved on the tap and then kept asking would have written a goal
 * the user was still describing. So every choice waits for Save, and the fields only appear
 * once CUSTOM is the selected row — a strength lifter never sees them.
 *
 * The step field is seeded with the increment the engine would use anyway (the rack's own
 * smallest step when the user has never set one), so saving without touching it writes down
 * the same number the suggestion was already using. It is typed and parsed in the current unit
 * system through the same conversions as `BarWeightDialog`, and the bound is applied on the
 * stored milligrams for the same reason that dialog gives.
 *
 * @param customIncrement the step to seed the field with, in milligrams — the stored value or
 *   the derived one, whichever the engine would read; the screen decides which.
 * @param onConfirm the goal, and under CUSTOM the range and the step in milligrams; null for
 *   both under any other goal, since nothing was asked.
 */
@Composable
fun TrainingGoalDialog(
    goal: TrainingGoal,
    customRepRange: RepRange,
    customIncrement: Long,
    unitSystem: UnitSystem,
    onConfirm: (TrainingGoal, RepRange?, Long?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local and `rememberSaveable`, per `BarWeightDialog`: a half-typed range is nobody else's
    // business, and a rotation must not throw it away.
    var selected by rememberSaveable(goal) { mutableStateOf(goal) }
    var minText by rememberSaveable(customRepRange) { mutableStateOf(customRepRange.min.toString()) }
    var maxText by rememberSaveable(customRepRange) { mutableStateOf(customRepRange.max.toString()) }
    var stepText by rememberSaveable(customIncrement, unitSystem) {
        mutableStateOf(formatWeightValue(customIncrement, unitSystem))
    }

    val range = repRangeOrNull(minText, maxText)
    val step = parseWeightMilligrams(stepText, unitSystem)?.takeIf { it > 0 }
    val isCustom = selected == TrainingGoal.CUSTOM
    val isValid = !isCustom || (range != null && step != null)

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_training_goal),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                TrainingGoal.entries.forEach { choice ->
                    ChoiceRow(
                        choice = SettingsChoice(value = choice, label = choice.label(), detail = choice.detail()),
                        isSelected = choice == selected,
                        onSelect = { selected = choice },
                    )
                }
                if (isCustom) {
                    CustomFields(
                        minText = minText,
                        maxText = maxText,
                        stepText = stepText,
                        isRangeValid = range != null,
                        isStepValid = step != null,
                        unitSystem = unitSystem,
                        onMinChange = { minText = it },
                        onMaxChange = { maxText = it },
                        onStepChange = { stepText = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    if (isCustom) onConfirm(selected, range, step) else onConfirm(selected, null, null)
                },
            ) {
                Text(text = stringResource(R.string.settings_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_dialog_cancel))
            }
        },
    )
}

/**
 * The two questions CUSTOM asks: the rep range as a pair of whole numbers side by side, and
 * the weight step in the user's unit.
 *
 * One error line for the pair rather than one per field, because the failure is usually
 * *between* them — `10` to `6` is two valid numbers and no range — and two fields each
 * claiming to be fine while the Save button stays grey is a dialog that will not say why.
 */
@Composable
@Suppress("LongParameterList") // Nine values for three fields; a state holder would be the same nine with a name.
private fun CustomFields(
    minText: String,
    maxText: String,
    stepText: String,
    isRangeValid: Boolean,
    isStepValid: Boolean,
    unitSystem: UnitSystem,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit,
    onStepChange: (String) -> Unit,
) {
    val showRangeError = !isRangeValid && (minText.isNotBlank() || maxText.isNotBlank())
    val showStepError = !isStepValid && stepText.isNotBlank()

    Column(
        modifier = Modifier.padding(top = TitanTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            OutlinedTextField(
                value = minText,
                onValueChange = onMinChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = showRangeError,
                label = { Text(text = stringResource(R.string.settings_custom_reps_from)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = maxText,
                onValueChange = onMaxChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = showRangeError,
                label = { Text(text = stringResource(R.string.settings_custom_reps_to)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (showRangeError) {
            Text(
                text = stringResource(R.string.settings_custom_reps_invalid, RepRange.MIN_REPS, RepRange.MAX_REPS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = stepText,
            onValueChange = onStepChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = showStepError,
            label = {
                Text(
                    text = stringResource(R.string.settings_custom_increment_field_label, unitSystem.weightSuffix),
                )
            },
            supportingText = if (showStepError) {
                { Text(text = stringResource(R.string.settings_custom_increment_invalid)) }
            } else {
                { Text(text = stringResource(R.string.settings_custom_increment_detail)) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

/**
 * The range the two fields describe, or null while they do not describe one — a blank, a
 * non-number, or a pair [RepRange] refuses. Parsed the way the set log parses a rep count.
 */
private fun repRangeOrNull(
    minText: String,
    maxText: String,
): RepRange? {
    val min = minText.trim().toIntOrNull() ?: return null
    val max = maxText.trim().toIntOrNull() ?: return null
    return try {
        RepRange(min = min, max = max)
    } catch (_: IllegalArgumentException) {
        null
    }
}
