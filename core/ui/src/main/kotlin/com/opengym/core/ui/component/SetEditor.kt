package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A set that has already been logged, reopened to be corrected.
 *
 * ### Why it is the row rather than a sheet
 *
 * A mistyped weight is corrected by looking at the set above it and the set below it — 60, 70,
 * *85*, 80 is how you notice the third one is wrong — and a bottom sheet covers exactly those
 * two rows at the moment they are needed. So the row stays where it is in the table and turns
 * into fields in place: the same grid, the same column widths, the same tint the row being typed
 * carries, and the rest of the workout still on screen behind it. It is [SetLogRow] with its
 * fields already filled in.
 *
 * ### Everything is in the row
 *
 * All four things a correction can change are cells of the row itself. The weight and the reps are
 * fields; the rating is the rating cell, which opens a menu ([RpePicker]); the set type is the
 * leading cell, which is where the type is *read* and so is also where it is picked
 * ([SetTypePicker]). Nothing is stacked underneath.
 *
 * That is a deliberate reversal. This used to draw the nine-button rating scale and a scrolling row
 * of five type chips open below the row — three blocks of chrome, taller together than the table
 * they were correcting, which pushed the neighbouring sets off the screen at the moment the whole
 * design rests on their being visible. Both controls are closed vocabularies picked rarely, which
 * is what a menu is for, and a menu is a popup: it costs no layout at all.
 *
 * The set's note is still *not* here. It is the one field of a set that is prose rather than a
 * quantity, it is written while the set is being logged, and a text field in the middle of a table
 * would push the rows this exists to be compared against off the screen. A caller must therefore
 * carry the stored note through the edit rather than dropping it — see `EditSetUseCase`.
 *
 * ### The two ways out
 *
 * The tick writes it and CANCEL leaves it as it was. There is deliberately no third: deleting a set
 * is a *gesture* on the row — dragged off to the left, see [SwipeToDelete] — rather than a word in
 * this panel. A DELETE the width of a thumb, one line under the fields, on a panel opened to fix a
 * digit is the button that costs somebody a set they had done; the drag cannot be performed by
 * accident, and it does not need a panel open to reach.
 *
 * @param setNumber the set's place in its exercise's table, from 1. What every label in here
 *   names, so a screen reader says which set is being corrected.
 * @param weight the weight field's contents, in the ambient
 *   [com.opengym.core.ui.theme.LocalUnitSystem]'s unit. Text rather than a number for the reason
 *   [SetLogRow] gives at length.
 * @param reps the rep field's contents.
 * @param rpe the rating as stored (× 10), or null for a set that was never rated.
 * @param onRpeChange the new rating, or null when the menu's "not rated" is chosen — see
 *   [RpePicker].
 * @param setType what kind of set it is.
 * @param onSetTypeChange the type picked from the leading cell's menu.
 * @param previous what was done on this set last time, formatted by the caller (#101). Kept so
 *   the open row's columns still line up with the rows above and below it.
 * @param showPrevious must match what the rest of the table was given, or the open row's cells
 *   sit under the wrong labels.
 * @param error why the values as typed would be refused, from the same `SetValidator` the entry
 *   row is checked against. Shown under the row and announced on the fields that carry it.
 * @param enabled false makes the whole editor read-only — a session that has been paused or is
 *   on its way out. The values stay on screen, because they are still the user's copy of them.
 * @param canSave whether the tick can be pressed. Narrower than [enabled] for the reason
 *   [SetLogRow]'s own tick is: a row can be perfectly editable and not currently hold a set the
 *   write path would accept.
 */
@Composable
@Suppress("LongParameterList")
fun SetEditor(
    setNumber: Int,
    weight: String,
    reps: String,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    rpe: Int?,
    onRpeChange: (Int?) -> Unit,
    setType: SetType,
    onSetTypeChange: (SetType) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    previous: String? = null,
    showPrevious: Boolean = true,
    error: String? = null,
    enabled: Boolean = true,
    canSave: Boolean = true,
) {
    SetRowFrame(
        modifier = modifier,
        // The same tint the row being typed carries, because it is the same thing: the one row
        // on the page the user is working on.
        isActive = true,
        isCompleted = true,
        error = error,
        footer = { EditorActions(onCancel = onCancel, enabled = enabled) },
    ) {
        SetTypeCell(
            setNumber = setNumber,
            setType = setType,
            onSetTypeChange = onSetTypeChange,
            isActive = true,
            enabled = enabled,
        )
        PreviousCell(previous = previous, showPrevious = showPrevious)

        SetValueField(
            value = weight,
            onValueChange = onWeightChange,
            description = stringResource(R.string.set_log_weight_field, setNumber),
            keyboardType = KeyboardType.Decimal,
            enabled = enabled,
            error = error,
            modifier = Modifier.weight(WEIGHT_COLUMN_WEIGHT),
        )
        Text(
            text = MULTIPLICATION_SIGN,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.clearAndSetSemantics { },
        )
        SetValueField(
            value = reps,
            onValueChange = onRepsChange,
            description = stringResource(R.string.set_log_reps_field, setNumber),
            keyboardType = KeyboardType.Number,
            enabled = enabled,
            error = error,
            modifier = Modifier.weight(REPS_COLUMN_WEIGHT),
        )

        RpeValueCell(
            setNumber = setNumber,
            rpe = rpe,
            onRpeChange = onRpeChange,
            enabled = enabled,
        )
        SaveButton(setNumber = setNumber, onSave = onSave, enabled = enabled && canSave)
    }
}

/**
 * The tick that writes the correction.
 *
 * A plain button rather than the entry row's `IconToggleButton`: this set is already done, so
 * there is no checked state to announce — what the control does is save, and a toggle would
 * announce it as "ticked" over a set that was ticked ten minutes ago.
 */
@Composable
private fun SaveButton(setNumber: Int, onSave: () -> Unit, enabled: Boolean) {
    val spoken = stringResource(R.string.set_edit_save, setNumber)

    IconButton(
        onClick = onSave,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .size(TOGGLE_SIZE)
            .semantics { contentDescription = spoken },
    ) {
        Icon(imageVector = Icons.Filled.Check, contentDescription = null)
    }
}

/**
 * The way out that changes nothing.
 *
 * On the right, under the tick, because those two are the pair: one writes the row and the other
 * leaves it, and a thumb that has just been in the rep field is already on that side. Deliberately
 * neutral rather than accented — the accent is spent here on the tint and the tick, which is what
 * it is for.
 */
@Composable
private fun EditorActions(onCancel: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel, enabled = enabled) {
            Text(
                text = stringResource(R.string.set_edit_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Set editor", widthDp = 360)
@Composable
fun SetEditorPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SetEditorTable()
    }
}

@Preview(name = "Set editor, dark", widthDp = 360)
@Composable
fun SetEditorDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        SetEditorTable()
    }
}

@Preview(name = "Set editor - a refused correction", widthDp = 360)
@Composable
fun SetEditorInvalidPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        OpenGymCard(modifier = Modifier.fillMaxWidth().padding(OpenGymTheme.spacing.screen)) {
            SetLogHeader()
            HairlineDivider()
            SetEditor(
                setNumber = 2,
                weight = "70",
                reps = "0",
                onWeightChange = {},
                onRepsChange = {},
                rpe = 75,
                onRpeChange = {},
                setType = SetType.NORMAL,
                onSetTypeChange = {},
                onSave = {},
                onCancel = {},
                error = "reps must be within 1..10,000, was 0",
                canSave = false,
            )
        }
    }
}

/** The second set of three, open for correction, with the sets around it still readable. */
@Composable
private fun SetEditorTable() {
    OpenGymCard(modifier = Modifier.fillMaxWidth().padding(OpenGymTheme.spacing.screen)) {
        SetLogHeader()
        HairlineDivider()
        LoggedSetRow(
            setNumber = 1,
            set = ActiveSet(
                reps = 10,
                weight = 60_000_000,
                rpe = 60,
                isCompleted = true,
                completedAt = 1,
            ),
            onClick = {},
            onCompletedChange = {},
        )
        HairlineDivider()
        SetEditor(
            setNumber = 2,
            weight = "70",
            reps = "8",
            onWeightChange = {},
            onRepsChange = {},
            rpe = 75,
            onRpeChange = {},
            setType = SetType.WARMUP,
            onSetTypeChange = {},
            onSave = {},
            onCancel = {},
        )
        HairlineDivider()
        LoggedSetRow(
            setNumber = 3,
            set = ActiveSet(
                reps = 6,
                weight = 75_000_000,
                rpe = 85,
                isCompleted = true,
                completedAt = 1,
            ),
            onClick = {},
            onCompletedChange = {},
        )
    }
}
