package com.opengym.feature.history.plate.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.feature.history.R

/**
 * The target, typed rather than stepped to.
 *
 * The steppers are the fast path and this is the direct one — nobody presses `+10` eleven times
 * to get from 60 to 170. It is a dialog with a confirm button for the reason the settings
 * screen's bar-weight dialog is: a half-typed number is not an intention, and committing on
 * every keystroke would put `1` and `17` on the barbell on the way to `170`.
 *
 * The field is seeded with the current target rendered in the current unit system and read back
 * through the same conversion, so opening it and confirming without touching anything cannot
 * move the number — see `parseWeightMilligrams`, whose round trip is asserted over the whole
 * grid of loadable weights in `UnitRoundTripTest`.
 *
 * @param onConfirm the new target, in milligrams.
 */
@Composable
fun TargetWeightDialog(
    target: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unitSystem = LocalUnitSystem.current

    // Local, and `rememberSaveable` so a rotation does not throw away what has been typed. It is
    // not in the UI state because a half-typed number is not something the ViewModel has an
    // opinion about, and hoisting it would put a keystroke through it and back per character.
    var text by rememberSaveable(target, unitSystem) {
        mutableStateOf(formatWeightValue(target, unitSystem))
    }

    // Checked in the unit it is *stored* in, not the one it was typed in: a weight can be
    // positive in kilograms and round to zero milligrams. `parseWeightMilligrams` answers only
    // "is this a number"; the bound is this dialog's to apply.
    val milligrams = parseWeightMilligrams(text, unitSystem)?.takeIf { it > 0 }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.plate_target),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = text.isNotBlank() && milligrams == null,
                label = {
                    Text(
                        text = stringResource(
                            R.string.plate_target_field_label,
                            unitSystem.weightSuffix,
                        ),
                    )
                },
                supportingText = if (text.isNotBlank() && milligrams == null) {
                    { Text(text = stringResource(R.string.plate_target_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(enabled = milligrams != null, onClick = { milligrams?.let(onConfirm) }) {
                Text(text = stringResource(R.string.plate_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.plate_dialog_cancel))
            }
        },
    )
}
