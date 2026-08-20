package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatRpe
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * How hard the set was, on the scale lifters actually use.
 *
 * ### Why this is a picker and not a field
 *
 * RPE is stored as an integer ten times the rating ([WorkoutSet.rpe]), which makes it look like
 * a number to type. It is not one. The scale has an agreed vocabulary — 6 through 10 in halves,
 * where 10 is "nothing left" and 8 is "two more" — and everything outside it is noise: a text
 * field accepts `47`, `0` and `9.3`, none of which anybody means, and it costs a keyboard and
 * four taps to enter a value with nine possibilities. Nine buttons is the whole scale, one tap
 * each, and no invalid state to validate.
 *
 * The scale starts at 6 rather than at 1 because the bottom half of the 1–10 range is not used
 * in practice: everything below "six more reps left" is a warm-up, and a warm-up is recorded as
 * a set *type* rather than as a rating. [WorkoutSet]'s own bound is wider (1.0–10.0) on purpose
 * — it is a storage bound, and an import from an app whose scale runs lower must survive the
 * trip — so this offers the vocabulary while the model keeps the range.
 *
 * @param rpe the stored value (RPE × 10), or null when the set has not been rated.
 * @param onRpeChange the new stored value, or **null** when the user taps the value that is
 *   already selected — tapping the current rating clears it, which is the only way back to "not
 *   rated" and is why this is not a plain `(Int) -> Unit`.
 * @param enabled false makes the scale read-only without changing what it shows, matching the
 *   entry row it is opened under. It matters for a scale that was already open when the session
 *   was paused: the tap that *opens* it is the caller's to withhold, and this is the half that
 *   stops the one already on screen from still taking a rating.
 */
@Composable
fun RpeSelector(
    rpe: Int?,
    onRpeChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        // Split over two rows rather than scrolled sideways: nine cells across a 360dp screen
        // is 36dp each before any gaps, and a rating is a thing you hit without looking.
        RPE_SCALE.chunked(RPE_ROW).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            ) {
                row.forEach { value ->
                    RpeCell(
                        value = value,
                        selected = value == rpe,
                        onClick = { onRpeChange(value.takeIf { it != rpe }) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Holds the shorter row's cells to the same width as the longer one's, so the
                // grid reads as a grid rather than as two rows of different-sized buttons.
                repeat(RPE_ROW - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }

        Text(
            text = stringResource(R.string.rpe_scale_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RpeCell(
    value: Int,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = formatRpe(value)
    val spoken = stringResource(R.string.rpe_value, label)

    Surface(
        modifier = modifier
            .height(CELL_HEIGHT)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, style = OpenGymTheme.typography.numeric, maxLines = 1)
        }
    }
}

/**
 * The scale offered, as stored values: 6.0 to 10.0 in halves.
 *
 * Nine values. `WorkoutSet.RPE_MAX` is the ceiling and the last of these, which is the check
 * worth having — the list is written out rather than generated so that a change to the model's
 * bounds does not silently reshape the vocabulary the user is offered.
 */
private val RPE_SCALE = listOf(60, 65, 70, 75, 80, 85, 90, 95, WorkoutSet.RPE_MAX)

/** Cells per row: five then four, so the grid is widest at the top. */
private const val RPE_ROW = 5

private val CELL_HEIGHT: Dp = 44.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "RPE selector", widthDp = 360)
@Composable
fun RpeSelectorPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        RpeSelectorCatalogue()
    }
}

@Preview(name = "RPE selector, dark", widthDp = 360)
@Composable
fun RpeSelectorDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        RpeSelectorCatalogue()
    }
}

@Composable
private fun RpeSelectorCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        RpeSelector(rpe = 85, onRpeChange = {})
        RpeSelector(rpe = null, onRpeChange = {})
    }
}
