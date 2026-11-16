package com.titan.feature.session.active.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.SetMenuAction
import com.titan.core.ui.format.elevationSuffix
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.format.formatElevation
import com.titan.core.ui.format.parseElevationCentimetres
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R
import com.titan.feature.session.active.CardioDetailsEdit

/*
 * The climb and the steps of an endurance set — the two figures a set of cardio can carry that the
 * row has no column for.
 *
 * ### Why not two more columns
 *
 * The set row already fits a set number, a previous figure, two value fields, a rating and a tick
 * into a phone's width, and every column it gains is width taken from all the others on every set
 * of every exercise. Most sets never carry either figure — a bench press has no climb, and plenty
 * of runs are logged without one — so the cost would be paid by every row for the benefit of a
 * few. They live a tap away instead: an item in the set's own menu opens a sheet, and a line under
 * the row shows them once they are recorded. A set without them looks exactly as it did.
 */

/** The set menu's item for this sheet, for an endurance set. See [SetMenuAction]. */
@Composable
fun cardioDetailsAction(onClick: () -> Unit): SetMenuAction =
    SetMenuAction(
        label = stringResource(R.string.set_cardio_details_action),
        icon = Icons.Outlined.Terrain,
        onClick = onClick,
    )

/**
 * The figures under the row they belong to — `120 m climb · 8,400 steps` — drawn only when at least
 * one is recorded, and a shortcut back into the sheet.
 *
 * @param elevation as typed, in the system's elevation unit; blank for none.
 * @param steps as typed; blank for none.
 * @param enabled whether the line opens the sheet — false while the row cannot be written to, as
 *   the set's menu then offers no way in either.
 */
@Composable
fun CardioDetailsLine(
    setNumber: Int,
    elevation: String,
    steps: String,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val unitSystem = LocalUnitSystem.current
    val parts = buildList {
        parseElevationCentimetres(elevation, unitSystem)?.let {
            add(stringResource(R.string.set_cardio_climb, formatElevation(it, unitSystem)))
        }
        steps.trim().toIntOrNull()?.let { add(pluralStringResource(R.plurals.set_cardio_steps, it, formatCount(it))) }
    }
    if (parts.isEmpty()) return
    val text = parts.joinToString(" · ")
    val action = stringResource(R.string.set_cardio_edit, setNumber)

    TextButton(
        onClick = onEdit,
        enabled = enabled,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = text
            if (enabled) {
                onClick(label = action) {
                    onEdit()
                    true
                }
            }
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Terrain,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = TitanTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The sheet the set's menu raises: two fields, Cancel and Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioDetailsSheet(
    edit: CardioDetailsEdit,
    setNumber: Int,
    onElevationChange: (String) -> Unit,
    onStepsChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CardioDetailsForm(
            edit = edit,
            setNumber = setNumber,
            onElevationChange = onElevationChange,
            onStepsChange = onStepsChange,
            onSave = onSave,
            onCancel = onDismiss,
        )
    }
}

@Composable
private fun CardioDetailsForm(
    edit: CardioDetailsEdit,
    setNumber: Int,
    onElevationChange: (String) -> Unit,
    onStepsChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanTheme.spacing.screen)
            .padding(bottom = TitanTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.set_cardio_details_title, setNumber),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = edit.elevation,
            onValueChange = onElevationChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    stringResource(R.string.set_cardio_elevation_label, LocalUnitSystem.current.elevationSuffix),
                )
            },
            singleLine = true,
            isError = edit.error != null && edit.elevation.isNotBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = edit.steps,
            onValueChange = onStepsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.set_cardio_steps_label)) },
            singleLine = true,
            isError = edit.error != null && edit.steps.isNotBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
        edit.error?.let { error ->
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.set_cardio_cancel))
            }
            Button(onClick = onSave, enabled = edit.error == null, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.set_cardio_save))
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Cardio details", showBackground = true, widthDp = 360)
@Composable
fun CardioDetailsPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        Column {
            CardioDetailsLine(setNumber = 2, elevation = "120", steps = "8400", onEdit = {})
            CardioDetailsForm(
                edit = CardioDetailsEdit(
                    sessionExerciseId = 1,
                    setId = 2,
                    elevation = "120",
                    steps = "8400",
                    error = null,
                ),
                setNumber = 2,
                onElevationChange = {},
                onStepsChange = {},
                onSave = {},
                onCancel = {},
            )
        }
    }
}

@Preview(name = "Cardio details - dark", showBackground = true, widthDp = 360)
@Composable
fun CardioDetailsDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        Column {
            CardioDetailsLine(setNumber = 2, elevation = "", steps = "8400", onEdit = {})
            CardioDetailsForm(
                edit = CardioDetailsEdit(
                    sessionExerciseId = 1,
                    setId = 2,
                    elevation = "",
                    steps = "8400",
                    error = null,
                ),
                setNumber = 2,
                onElevationChange = {},
                onStepsChange = {},
                onSave = {},
                onCancel = {},
            )
        }
    }
}
