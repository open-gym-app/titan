package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatRpe
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * How hard the set was, picked from the cell that shows it.
 *
 * ### Why a menu, and not a field or a scale
 *
 * RPE is stored as an integer ten times the rating ([WorkoutSet.rpe]), which makes it look like a
 * number to type. It is not one. The scale has an agreed vocabulary — 6 through 10 in halves,
 * where 10 is "nothing left" and 8 is "two more" — and everything outside it is noise: a text
 * field accepts `47`, `0` and `9.3`, none of which anybody means, and it costs a keyboard and four
 * taps to enter one of nine possibilities.
 *
 * What this replaces was those nine possibilities as nine buttons, drawn as a two-row grid under
 * the row being rated. Nine cells is the whole scale in one tap and it reads beautifully in
 * isolation; in place it was a block the height of a card, opened *inside* a set table, which
 * pushed the two sets above and below it — the ones a rating is judged against — off the screen at
 * the moment they were needed. A menu is a popup: it costs no layout, it takes the same one tap to
 * open and one to choose, and the rows behind it stay where they are.
 *
 * The scale starts at 6 rather than at 1 because the bottom half of the 1–10 range is not used in
 * practice: everything below "six more reps left" is a warm-up, and a warm-up is recorded as a set
 * *type* rather than as a rating. [WorkoutSet]'s own bound is wider (1.0–10.0) on purpose — it is a
 * storage bound, and an import from an app whose scale runs lower must survive the trip — so this
 * offers the vocabulary while the model keeps the range.
 *
 * @param setNumber the set being rated, from 1. Named in what a screen reader says, since the cell
 *   holds one figure and a table of them says nothing about which.
 * @param rpe the stored value (RPE × 10), or null when the set has not been rated.
 * @param onRpeChange the new stored value, or **null** from the menu's own "not rated" item, which
 *   is the only way back to an unrated set and is why this is not a plain `(Int) -> Unit`.
 * @param enabled false leaves the value readable and the menu shut — a paused or finished session,
 *   whose writes the domain refuses anyway.
 */
@Composable
fun RpePicker(
    setNumber: Int,
    rpe: Int?,
    onRpeChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val spoken = if (rpe != null) {
        stringResource(R.string.set_log_rpe_value, setNumber, formatRpe(rpe))
    } else {
        stringResource(R.string.set_log_rpe_none, setNumber)
    }

    Box(
        modifier = modifier
            .width(RPE_COLUMN_WIDTH)
            .heightIn(min = PICKER_MIN_HEIGHT)
            .background(
                color = if (enabled) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                shape = MaterialTheme.shapes.extraSmall,
            )
            .clickable(
                enabled = enabled,
                role = Role.DropdownList,
                onClick = { expanded = true },
            )
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = rpe?.let(::formatRpe) ?: stringResource(R.string.set_log_rpe_unset),
            style = OpenGymTheme.typography.numericSmall,
            color = if (rpe == null) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        // `enabled` closes it as well as withholding it. A menu is a popup, so one opened
        // just before the workout was paused would otherwise stay up over a card that can no
        // longer be written to — and take a value out of the one gap in the read-only state.
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            // The vocabulary, kept from the grid this replaced. It is the one thing the nine
            // buttons said that a list of numbers does not, and it is read once — when a user is
            // deciding what the scale means — which is exactly when the menu is open.
            Text(
                text = stringResource(R.string.rpe_scale_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(MENU_WIDTH)
                    .padding(
                        horizontal = OpenGymTheme.spacing.xxl,
                        vertical = OpenGymTheme.spacing.md,
                    ),
            )
            HorizontalDivider()

            RPE_SCALE.forEach { value ->
                RpeMenuItem(
                    label = formatRpe(value),
                    spoken = stringResource(R.string.rpe_value, formatRpe(value)),
                    selected = value == rpe,
                    onClick = {
                        expanded = false
                        onRpeChange(value)
                    },
                )
            }

            HorizontalDivider()
            // Last rather than first: it is the way *back* from a rating, not one of the ratings,
            // and a user opening the menu is nine times out of ten about to pick a number.
            RpeMenuItem(
                label = stringResource(R.string.rpe_not_rated),
                spoken = stringResource(R.string.rpe_not_rated),
                selected = rpe == null,
                onClick = {
                    expanded = false
                    onRpeChange(null)
                },
            )
        }
    }
}

/** One rating in the menu: the figure, and a tick on the one the set already carries. */
@Composable
private fun RpeMenuItem(
    label: String,
    spoken: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                // The same mono figures the table is written in, so a rating picked here and the
                // same rating read back in a row are visibly the same value.
                style = OpenGymTheme.typography.numeric,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
        modifier = Modifier
            .width(MENU_WIDTH)
            .semantics { contentDescription = spoken },
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * The scale offered, as stored values: 6.0 to 10.0 in halves.
 *
 * Nine values. `WorkoutSet.RPE_MAX` is the ceiling and the last of these, which is the check worth
 * having — the list is written out rather than generated so that a change to the model's bounds
 * does not silently reshape the vocabulary the user is offered.
 */
private val RPE_SCALE = listOf(60, 65, 70, 75, 80, 85, 90, 95, WorkoutSet.RPE_MAX)

/**
 * How wide the menu is.
 *
 * Set on the items rather than left to the content, which would be as wide as `8.5` and give the
 * hint above them nowhere to wrap. Narrow enough to stay a menu next to a 32dp cell, wide enough
 * for the sentence that teaches the scale.
 */
private val MENU_WIDTH: Dp = 220.dp

/** As tall as the fields beside it, for the reason [SetTypePicker] gives at length. */
private val PICKER_MIN_HEIGHT: Dp = 40.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "RPE picker", widthDp = 360)
@Composable
fun RpePickerPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        RpePickerCatalogue()
    }
}

@Preview(name = "RPE picker, dark", widthDp = 360)
@Composable
fun RpePickerDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        RpePickerCatalogue()
    }
}

/** Rated, unrated, and read-only, as the three states a row's rating cell is drawn in. */
@Composable
private fun RpePickerCatalogue() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        RpePicker(setNumber = 1, rpe = 85, onRpeChange = {})
        RpePicker(setNumber = 2, rpe = null, onRpeChange = {})
        RpePicker(setNumber = 3, rpe = 100, onRpeChange = {}, enabled = false)
    }
}
