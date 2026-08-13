package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.plate.PlateCount
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What to pick up, as a list: one row per size, with the swatch that ties it to the diagram.
 *
 * The diagram answers *what shape is this bar*; this answers *what do I carry over from the
 * rack*, which is the question somebody standing in front of the rack actually has. The two are
 * shown together and neither replaces the other — which is also why the swatch is here. Without
 * it the list is a second, unconnected set of numbers.
 *
 * A bar with nothing on it draws nothing at all rather than an empty card with a heading: the
 * caller says "just the bar" in words, where it can also say what the bar weighs.
 *
 * @param perSide one end of the bar, heaviest first.
 * @param denominations every size the rack knows, heaviest first — the same list the diagram is
 *   given, so a size is the same colour in both.
 */
@Composable
fun PlateLegend(
    perSide: List<PlateCount>,
    denominations: List<Long>,
    modifier: Modifier = Modifier,
) {
    if (perSide.isEmpty()) return

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OpenGymTheme.spacing.card)) {
            Eyebrow(
                text = stringResource(R.string.plate_legend_per_side),
                modifier = Modifier.padding(bottom = OpenGymTheme.spacing.md),
            )

            perSide.forEachIndexed { index, plate ->
                if (index > 0) HairlineDivider()
                // `denominations.size` for a size the rack does not list — the lightest tone,
                // rather than the accent `indexOf`'s -1 would clamp to. See `BarbellDiagram`.
                PlateLegendRow(
                    plate = plate,
                    rank = denominations.indexOf(plate.weight).takeIf { it >= 0 } ?: denominations.size,
                )
            }
        }
    }
}

/**
 * One size: its colour, its weight, and how many of them.
 *
 * Announced as a single phrase — "two 20 kg" — because a swatch, a weight and a count read as
 * three unrelated nodes otherwise, and the multiplication sign is not a word.
 */
@Composable
private fun PlateLegendRow(plate: PlateCount, rank: Int) {
    val tone = OpenGymTheme.plateColors.toneFor(rank)
    val description = pluralStringResource(
        R.plurals.plate_count_description,
        plate.count,
        plate.count,
        formatWeight(plate.weight),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OpenGymTheme.spacing.md)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_SIZE)
                .background(tone.fill, RoundedCornerShape(SWATCH_RADIUS))
                // Outlined for the same reason the plates are: the pale end of the ramp is a
                // shade off the card it sits on.
                .border(
                    width = HAIRLINE_WIDTH,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(SWATCH_RADIUS),
                ),
        )
        Text(
            text = formatWeight(plate.weight),
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.plate_legend_count, plate.count),
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Big enough to read as the plate's colour, small enough not to read as a control. */
private val SWATCH_SIZE = 16.dp
private val SWATCH_RADIUS = 3.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Composable
private fun PlateLegendCatalogue() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
    ) {
        PlateLegend(
            perSide = listOf(
                PlateCount(weight = 20_000_000, count = 2),
                PlateCount(weight = 10_000_000, count = 1),
                PlateCount(weight = 1_250_000, count = 1),
            ),
            denominations = PlateInventory.METRIC.stock.map { it.weight },
        )
    }
}

@Preview(name = "Plate legend", widthDp = 360)
@Composable
fun PlateLegendPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { PlateLegendCatalogue() }
}

@Preview(name = "Plate legend, dark", widthDp = 360)
@Composable
fun PlateLegendDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { PlateLegendCatalogue() }
}
