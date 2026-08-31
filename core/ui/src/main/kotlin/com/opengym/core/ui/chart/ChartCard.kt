package com.opengym.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A chart in a card, under its own heading and over its own axis.
 *
 * ```text
 * ┌──────────────────────────────────┐
 * │ 1RM ESTIMATE            [ kg ]   │
 * │                                  │
 * │        ╭──╮      ╭───●           │
 * │  ╭─────╯  ╰──────╯               │
 * │ ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒  │
 * │ FEB          MAR          APR    │
 * └──────────────────────────────────┘
 * ```
 *
 * Four things that always travel together — the bordered surface, the eyebrow that names the
 * series, the plot, and the sparse period labels under it — and the reason they are one
 * component is the axis: [chartAxisLabels] has to be given the *same* entries the chart was,
 * or the months sit over marks they do not cover. Passing the series once and letting the card
 * do both is what makes that impossible to get wrong, and it is the shape #109's body-weight
 * card and #110's eight-week card take too.
 *
 * The plot itself is a slot rather than a flag, so a card can hold whichever of the charts
 * suits its question — [LineSeriesChart] for "which way is this going", [BarSeriesChart] for
 * "which of these was the big one" — and can pass it a height without this component growing a
 * parameter per chart.
 *
 * **Not what the stats hub's hero is.** That card carries a large figure and a change chip
 * *between* the eyebrow and the plot, which is a different arrangement rather than this one
 * with a slot added: the chip belongs to the figure's baseline, not to the heading. See
 * `VolumeHeroCard`.
 *
 * @param label what the series is, in sentence case. Drawn as the eyebrow, which uppercases
 *   it, and handed to [chart] for the description a screen reader is given — so the card and
 *   the plot inside it cannot end up calling the series two different things.
 * @param entries the series. Given to [chart] and to the axis, which is the whole point.
 * @param trailing a tag at the end of the heading row: the unit the plot is in, a count. Null
 *   draws nothing and the eyebrow takes the width.
 * @param chart the plot. Called with exactly [entries] and [label].
 */
@Composable
fun ChartCard(
    label: String,
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    chart: @Composable (entries: List<ChartEntry>, label: String) -> Unit,
) {
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
            // The plot and its labels sit tighter than the card's own rhythm: the months are
            // part of the drawing rather than a second block under it.
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
                chart(entries, label)
                ChartAxisLabels(labels = chartAxisLabels(entries))
            }
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
            trailing = { OpenGymBadge(text = "kg") },
        ) { entries, label ->
            LineSeriesChart(entries = entries, label = label, height = ChartDefaults.TALL_HEIGHT)
        }
        ChartCard(label = "Volume per session", entries = previewPeaked()) { entries, label ->
            BarSeriesChart(entries = entries, label = label)
        }
        ChartCard(label = "Volume per session", entries = previewSinglePoint()) { entries, label ->
            BarSeriesChart(entries = entries, label = label)
        }
    }
}
