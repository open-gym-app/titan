package com.opengym.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A chart in a card, under its own heading and between its two axes.
 *
 * ```text
 * ┌──────────────────────────────────┐
 * │ 1RM ESTIMATE            [ kg ]   │
 * │      ┌──────────┐                │
 * │  95 ·│12 APR · 92.5│· · · ·╭──╮  │
 * │      └─────┬────┘      ╭───╯  ●  │
 * │  88 · · · ·●· · · ╭────╯         │
 * │  82 ─╭─────╯                     │
 * │       FEB       MAR       APR    │
 * └──────────────────────────────────┘
 * ```
 *
 * Everything that always travels together — the bordered surface, the eyebrow that names the
 * series, the plot, the figures it is read against and the periods it runs over — and the
 * reason they are one component is that none of the four can be built without the series and
 * the scale. Handing them over once is what makes it impossible for the months to sit over
 * marks they do not cover, or for a rail to be labelled against a band the plot was not drawn
 * to. [ChartPlot] is the same thing without the card, for a caller that has its own heading.
 *
 * **This is the stateful half.** It remembers which mark the tooltip is open on, which is the
 * right default for a card whose selection is nobody else's business, and resets it when the
 * series changes — a timeframe switched under an open tooltip would otherwise leave it
 * pointing at whatever now happens to be mark seven. Drive [ChartPlot] directly to hoist it.
 *
 * **Not what a hero card is.** [SeriesHeroCard] carries a large figure and a change chip
 * *between* the eyebrow and the plot, which is a different arrangement rather than this one
 * with a slot added: the chip belongs to the figure's baseline, not to the heading, and the
 * figure is what its marks are read against, so it draws no value rail.
 *
 * @param label what the series is, in sentence case. Drawn as the eyebrow, which uppercases
 *   it, and handed to the plot for the description a screen reader is given — so the card and
 *   the chart inside it cannot end up calling the series two different things.
 * @param entries the series. Given to the chart, to both axes and to the tooltip.
 * @param scale which band the chart is read against, and so which chart it is — see
 *   [ChartScale], and [ChartPlot] for why this is not a slot.
 * @param formatValue the caller's own formatter for the figures down the left, without a unit.
 *   Null draws no value axis; see [chartValueTicks].
 * @param trailing a tag at the end of the heading row: the unit the plot is in, a count. Null
 *   draws nothing and the eyebrow takes the width.
 */
@Composable
fun ChartCard(
    label: String,
    entries: List<ChartEntry>,
    scale: ChartScale,
    modifier: Modifier = Modifier,
    height: Dp = ChartDefaults.HEIGHT,
    formatValue: ((Float) -> String)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var selected by rememberSaveable(entries) { mutableStateOf<Int?>(null) }

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow(text = label, modifier = Modifier.weight(1f))
                if (trailing != null) trailing()
            }
            ChartPlot(
                entries = entries,
                label = label,
                scale = scale,
                height = height,
                formatValue = formatValue,
                selectedIndex = selected,
                onSelect = { selected = it },
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Chart card", widthDp = 360)
@Composable
fun ChartCardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ChartCardCatalogue()
    }
}

@Preview(name = "Chart card, dark", widthDp = 360)
@Composable
fun ChartCardDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ChartCardCatalogue()
    }
}

@Composable
private fun ChartCardCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        ChartCard(
            label = "One-rep max estimate",
            entries = previewRising(),
            scale = ChartScale.LINE,
            height = ChartDefaults.TALL_HEIGHT,
            formatValue = ::previewFormat,
            trailing = { OpenGymBadge(text = "kg") },
        )
        ChartCard(
            label = "Volume per session",
            entries = previewPeaked(),
            scale = ChartScale.BAR,
            formatValue = ::previewFormat,
        )
        ChartCard(
            label = "Volume per session",
            entries = previewSinglePoint(),
            scale = ChartScale.BAR,
            formatValue = ::previewFormat,
        )
    }
}
