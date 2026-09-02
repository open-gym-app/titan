package com.opengym.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The figure a screen is about, what it is measured in, how far it moved, and the series
 * behind it.
 *
 * ```text
 * ┌──────────────────────────────────┐
 * │ BODY WEIGHT · 90 DAYS            │
 * │ 78.4 kg                [ ↓ 1.2 ] │
 * │  ╰─╮   ╭──╮                      │
 * │    ╰───╯  ╰────╮   ╭──╮          │
 * │                ╰───╯  ●          │
 * │  FEB       MAR       APR         │
 * └──────────────────────────────────┘
 * ```
 *
 * ### Not [ChartCard] with a slot added
 *
 * The two cards put the same parts in a different order, and the order is the point. In
 * [ChartCard] the plot *is* the card and the heading names it, so a rail of figures down the
 * left is what the marks are read against. Here the **figure** is the card — the total, the
 * latest weight — and the series under it is the shape that figure came from, so there is no
 * rail: the number above it already says what the last mark is worth, and a second column of
 * figures would be the same answer twice. The change chip belongs to the figure's baseline
 * rather than to the heading, which is the arrangement neither card can borrow from the other.
 *
 * ### Why it is in `:core:ui` rather than on the screen that drew it first
 *
 * #32's cumulative-volume hero was the first, and #M5-15's body weight is the second: an
 * eyebrow, a mono figure, a quiet unit beside it, a change chip pushed to the end, the plot
 * and its period labels. That is #144's argument arriving on schedule — the first screen to
 * need a chart invents one and the rest copy it — so the arrangement moved here the moment
 * there were two of them, rather than after four.
 *
 * ### One accent, and the figure is not it
 *
 * The chip takes the accent on a rise and nothing else does; the figure is drawn in the
 * ordinary text tone however it moved. A hero number painted in the accent beside a chip that
 * is also accented spends it twice on one fact — see [ChangeChip], which explains why a fall
 * is not the error role either.
 *
 * **This is the stateful half**, like [ChartCard]: it remembers which mark the tooltip is open
 * on and forgets it when the series changes, so a timeframe switched under an open tooltip
 * does not leave it pointing at whatever is now mark seven.
 *
 * @param label what the series is, in sentence case — the eyebrow uppercases it, and the plot
 *   opens its spoken description with the same string.
 * @param value the figure, already formatted and without its unit. Drawn in the large numeric
 *   role, so two hero cards on one screen line up on the digit.
 * @param unit what [value] is measured in: `kg`, `cm`. Null closes the gap up, for a figure
 *   that is a count.
 * @param entries the series behind the figure. Fewer than two marks draws the placeholder
 *   rather than a degenerate plot — see [ChartPlaceholder].
 * @param change how far the figure moved over the window, or null when there is nothing behind
 *   it to compare against.
 * @param scale which band the series is read against — see [ChartScale]. A hero is usually a
 *   [ChartScale.LINE], because a figure with a shape under it is being asked which way it is
 *   going.
 */
@Composable
fun SeriesHeroCard(
    label: String,
    value: String,
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    unit: String? = null,
    change: ChartChange? = null,
    scale: ChartScale = ChartScale.LINE,
    height: Dp = ChartDefaults.HEIGHT,
) {
    var selected by rememberSaveable(entries) { mutableStateOf<Int?>(null) }

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            Eyebrow(text = label)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                // The baseline the figure sits on, so a chip and a unit half its height hang
                // off the bottom of the digits rather than floating beside their middle.
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = value,
                    style = OpenGymTheme.typography.numericLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                // The unit takes the weight rather than the figure: a five-digit total should
                // push the chip to the edge, not be ellipsised by it.
                Text(
                    text = unit.orEmpty(),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (change != null) ChangeChip(change = change)
            }
            ChartPlot(
                entries = entries,
                label = label,
                scale = scale,
                height = height,
                // No rail. The figure above the plot is what the marks are read against; see
                // the note on this card against [ChartCard].
                formatValue = null,
                selectedIndex = selected,
                onSelect = { selected = it },
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Series hero card", widthDp = 360)
@Composable
fun SeriesHeroCardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SeriesHeroCardCatalogue()
    }
}

@Preview(name = "Series hero card, dark", widthDp = 360)
@Composable
fun SeriesHeroCardDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        SeriesHeroCardCatalogue()
    }
}

@Composable
private fun SeriesHeroCardCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        SeriesHeroCard(
            label = "Cumulative volume",
            value = "184,320",
            unit = "kg",
            entries = previewRising(),
            change = ChartChange(direction = ChangeDirection.UP, value = "18%"),
        )
        SeriesHeroCard(
            label = "Body weight",
            value = "78.4",
            unit = "kg",
            entries = previewFlat(),
            change = ChartChange(direction = ChangeDirection.NONE, value = "0 kg"),
        )
        // Nothing to compare against and nothing plottable: the first entry a user logs.
        SeriesHeroCard(label = "Body weight", value = "78.4", unit = "kg", entries = previewSinglePoint())
    }
}
