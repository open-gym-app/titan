package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The wireframes' section tabs: mono labels in a ruled strip, the selected one underlined in
 * the accent.
 *
 * Not Material's `TabRow`, for the reason [OpenGymCard] is not Material's `Card`. `TabRow`
 * distributes its tabs across the full width and draws a rounded, animated indicator inset
 * from the label; this design puts them at the start of a ruled strip with a square 2dp rule
 * under the selected one, and reaching that through `TabRow`'s indicator and divider slots
 * means overriding both of the things it does for you and keeping the third — equal-width
 * distribution — that the design does not want. Three tabs spread across 360dp put
 * `TEMPLATES` in the middle of nowhere; packed at the start they read as a heading with
 * alternatives beside it.
 *
 * The accent here is doing its ordinary job — marking the one thing that is currently active
 * — and the unselected labels are quiet rather than merely lighter: `onSurfaceVariant`
 * against `onSurface`, which is a role difference a screen reader and a contrast checker can
 * both see.
 *
 * A `selectableGroup` of `Role.Tab` nodes, so TalkBack announces "tab 2 of 3" rather than
 * three unrelated buttons.
 *
 * @param labels sentence case; the row uppercases them, as [Eyebrow] does and for the same
 *   reason — a translator should never be asked to reproduce a typographic treatment.
 * @param selectedIndex which tab is showing. Out-of-range selects nothing rather than
 *   throwing, which is the right failure for a value restored from saved state after the
 *   tabs have changed.
 */
@Composable
fun OpenGymTabRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OpenGymTheme.spacing.md)
                .selectableGroup(),
        ) {
            labels.forEachIndexed { index, label ->
                TabLabel(
                    label = label,
                    selected = index == selectedIndex,
                    onSelect = { onSelect(index) },
                )
            }
        }
        // The wireframes rule off every strip rather than floating it on elevation. The
        // selected tab's own rule is drawn on top of this one, at twice the weight and in
        // the accent, which is what makes it read as a continuation of the tab rather than
        // as a second line under it.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * One tab: its label, and the rule under it.
 *
 * The rule is always drawn and only sometimes coloured, so selecting a tab changes no
 * layout — a transparent 2dp box holds the space an accent one will need. Without that the
 * whole strip moves by 2dp on every tab change.
 */
@Composable
private fun TabLabel(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onSelect)
            // Sized to its own label. Without this the indicator's `fillMaxWidth` below
            // resolves against the *row's* remaining width rather than the tab's, and the
            // first tab silently takes the whole strip — leaving the other two at zero width
            // and untappable.
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow(
            text = label,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(
                horizontal = OpenGymTheme.spacing.lg,
                vertical = OpenGymTheme.spacing.lg,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(INDICATOR_HEIGHT)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
        )
    }
}

/**
 * Twice the hairline, because it has to read as a *state* rather than as another rule on a
 * screen that is full of them. Not a shape token: it is a thickness, and the shape scale is
 * a scale of radii.
 */
private val INDICATOR_HEIGHT = 2.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private val PREVIEW_LABELS = listOf("History", "Templates", "Calendar")

@Preview(name = "Tab row", widthDp = 360)
@Composable
fun OpenGymTabRowPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        OpenGymTabRow(labels = PREVIEW_LABELS, selectedIndex = 0, onSelect = {})
    }
}

@Preview(name = "Tab row - dark", widthDp = 360)
@Composable
fun OpenGymTabRowDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        OpenGymTabRow(labels = PREVIEW_LABELS, selectedIndex = 2, onSelect = {})
    }
}
