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
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.format.weightToMilligrams
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

/**
 * The barbells the presets offer, per unit system.
 *
 * Two lists rather than one converted list, because a bar is a manufactured object and its
 * weight is a round number in the system it was made for: a metric lifter's bars are 20, 15
 * and 10 kg, an imperial lifter's are 45, 35 and 25 lb. Converting the metric set would offer
 * an American user "44.1 lb", which is the weight of a bar nobody sells.
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
 * anything cannot move the value — see `weightToMilligrams`.
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

    val entered = text.parseWeight()
    // Validated in the unit it is *stored* in, not the one it was typed in. A weight can be
    // positive in kilograms and round to zero milligrams — and `setBarWeight` rejects zero,
    // so a field that only checked the typed number would hand the ViewModel a value the
    // repository throws on.
    val milligrams = entered
        ?.takeIf { it > 0.0 && it.isFinite() }
        ?.let { weightToMilligrams(it, unitSystem) }
        ?.takeIf { it > 0 }
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
                        val label = "${formatPreset(preset)} ${unitSystem.weightSuffix}"
                        FilterChip(
                            selected = entered == preset,
                            onClick = { text = formatPreset(preset) },
                            label = { Text(text = label) },
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

/** A preset without its trailing `.0`, so a 20 kg bar's chip reads `20 kg`. */
private fun formatPreset(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * The typed weight, or null when it is not a number.
 *
 * Two passes, in this order:
 *
 * 1. **ASCII, with either separator.** A phone keyboard is not required to agree with the
 *    locale — a French user on an English keyboard types `22.5` and means 22.5, and refusing
 *    it would be the app being right about something nobody asked. This pass first is also
 *    what makes `22.5` mean 22.5 in a locale where `.` groups thousands.
 * 2. **The locale's own numerals**, which is what the field is *seeded* with:
 *    `formatWeightValue` renders through the default locale, so in Persian, Arabic-Egyptian,
 *    Bengali and a dozen others the stored 20 kg arrives as `۲۰` — digits [String.toDouble]
 *    has never heard of. Without this pass the dialog would open in its own error state with
 *    Save disabled, on a value the app itself wrote.
 */
private fun String.parseWeight(): Double? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    return trimmed.replace(',', '.').toDoubleOrNull() ?: trimmed.parseLocalizedNumber()
}

/**
 * [NumberFormat] parses leniently — it reads `12abc` as 12 and stops — so the position it
 * ends at is checked against the length: a partial parse is a typo, not a weight.
 */
private fun String.parseLocalizedNumber(): Double? {
    val position = ParsePosition(0)
    val parsed = NumberFormat.getInstance(Locale.getDefault()).parse(this, position)
    return parsed?.toDouble()?.takeIf { position.index == length }
}
