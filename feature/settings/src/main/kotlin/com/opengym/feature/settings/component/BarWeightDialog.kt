package com.opengym.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.format.weightToMilligrams
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R

/**
 * The barbells the presets offer, per unit system.
 *
 * Two lists rather than one converted list, because a bar is a manufactured object and its
 * weight is a round number in the system it was made for: a metric lifter's bars are 20, 15
 * and 10 kg, an imperial lifter's are 45, 35 and 25 lb. Converting the metric set would offer
 * an American user "44.09 lb", which is the weight of a bar nobody sells.
 */
private val METRIC_BARS = listOf(20.0, 15.0, 10.0)
private val IMPERIAL_BARS = listOf(45.0, 35.0, 25.0)

/**
 * The bar weight the plate calculator works up from: three common bars, and a field for
 * anything else.
 *
 * The one setting that is a *quantity* rather than a choice, so it is the one dialog with a
 * confirm button: a half-typed number is not an intention, and saving on every keystroke
 * would store `2` on the way to `20`.
 *
 * The field is seeded with the stored weight rendered in the current unit system and read
 * back through the same conversion, so opening the dialog and saving without touching
 * anything cannot move the value — see `parseWeightMilligrams`, whose round trip is asserted
 * over the whole grid of loadable weights in `UnitRoundTripTest`.
 *
 * @param onConfirm the new bar weight, in milligrams.
 */
@Composable
fun BarWeightDialog(
    barWeight: Long,
    unitSystem: UnitSystem,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local, and `rememberSaveable` so a rotation does not throw away what has been typed.
    // It is not in `SettingsUiState` because a half-typed number is not something any other
    // part of the app has an opinion about, and hoisting it would put a keystroke through the
    // ViewModel and back on every character.
    var text by rememberSaveable(barWeight, unitSystem) {
        mutableStateOf(formatWeightValue(barWeight, unitSystem))
    }

    // Parsed and range-checked in the unit it is *stored* in, not the one it was typed in. A
    // weight can be positive in kilograms and round to zero milligrams — and `setBarWeight`
    // rejects zero, so a field that only checked the typed number would hand the ViewModel a
    // value the repository throws on. `parseWeightMilligrams` answers only "is this a
    // number"; the bound is this dialog's to apply.
    val milligrams = parseWeightMilligrams(text, unitSystem)?.takeIf { it > 0 }
    val isValid = milligrams != null

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_bar_weight),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
                    val presets = when (unitSystem) {
                        UnitSystem.METRIC -> METRIC_BARS
                        UnitSystem.IMPERIAL -> IMPERIAL_BARS
                    }
                    presets.forEach { preset ->
                        val presetMilligrams = weightToMilligrams(preset, unitSystem)
                        val presetText = formatWeightValue(presetMilligrams, unitSystem)
                        FilterChip(
                            // Compared as stored milligrams rather than as text, so a chip
                            // still reads as selected when the field holds the same weight
                            // written differently — `20.0`, or an ASCII `20` in a locale
                            // whose own digits are not.
                            selected = milligrams == presetMilligrams,
                            onClick = { text = presetText },
                            label = { Text(text = "$presetText ${unitSystem.weightSuffix}") },
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = text.isNotBlank() && !isValid,
                    label = {
                        Text(
                            text = stringResource(
                                R.string.settings_bar_weight_field_label,
                                unitSystem.weightSuffix,
                            ),
                        )
                    },
                    supportingText = if (text.isNotBlank() && !isValid) {
                        { Text(text = stringResource(R.string.settings_bar_weight_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { milligrams?.let(onConfirm) },
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
