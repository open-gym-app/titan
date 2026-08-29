package com.opengym.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A series as bars, with the single heaviest drawn in the accent and the rest in the deep fill.
 *
 * ```text
 *                     █
 *        ▓     ▓  ▓   █   ▓  ▓
 *  ▓  ▓  ▓  ▓  ▓  ▓   █   ▓  ▓
 * ```
 *
 * The exercise screen's volume-per-session card and Home's eight-week card. Where
 * [LineSeriesChart] answers "which way is this going", bars answer "which of these was the
 * big one" — so they are measured against zero rather than against the series' own floor, and
 * the emphasis is the peak rather than the latest.
 *
 * ### One accent, and only when there is a peak
 *
 * The highest bar is the accent and every other bar is `surfaceContainerHighest`, matching the
 * wireframes' `peak` treatment. A series with **no unique maximum** — a flat week, a tie for
 * the top — accents nothing: the accent means *this one*, and painting the first of three
 * equal bars is the component inventing a distinction the data does not carry. See
 * [ChartStats.peakIndex].
 *
 * ### A zero week is a mark, not a gap
 *
 * Every bar draws at least [ChartDefaults.BAR_BASELINE], so a fortnight off is visibly a
 * fortnight of nothing rather than a hole where the chart forgot to draw. The whole series
 * being zero is the same case, and does not divide by anything — see [ChartStats.barFraction].
 *
 * ### Fewer than two bars draws nothing
 *
 * See [ChartPlaceholder]. A lone bar fills its own track and reads as a peak, which is a claim
 * about a comparison that has not been made.
 *
 * @param label what the series is, for the description a screen reader is given — `Volume per
 *   session`. Not drawn: the card around the chart carries its own eyebrow.
 * @param height how tall the plot is. The width is whatever the caller gives it.
 */
@Composable
fun BarSeriesChart(
    entries: List<ChartEntry>,
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = ChartDefaults.HEIGHT,
) {
    val spoken = chartDescription(label = label, entries = entries)
    if (!entries.isPlottable()) {
        ChartPlaceholder(label = label, modifier = modifier, height = height)
        return
    }

    val stats = ChartStats(entries)
    val peak = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest

    Canvas(
        // One node for the whole plot, for the reason [LineSeriesChart] gives: a screen reader
        // walking twelve bars never reaches the figure the card is about.
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        drawBarSeries(stats = stats, values = entries.map { it.value }, peak = peak, fill = fill)
    }
}

/**
 * The bars themselves.
 *
 * The gap is taken out of each bar's slot rather than added between slots, so the series
 * always spans the full width however many bars it holds. On a narrow card with a long series
 * that would eventually leave nothing to draw, so the gap is given up before the bar is —
 * [ChartDefaults.MIN_BAR_WIDTH] is the floor, and a series dense enough to reach it is drawn
 * as a solid block, which is the honest picture of a resolution the width cannot show.
 */
private fun DrawScope.drawBarSeries(
    stats: ChartStats,
    values: List<Float>,
    peak: Color,
    fill: Color,
) {
    val slot = size.width / values.size
    val gap = ChartDefaults.BAR_GAP.toPx()
    val width = (slot - gap).coerceAtLeast(minOf(slot, ChartDefaults.MIN_BAR_WIDTH.toPx()))
    val baseline = ChartDefaults.BAR_BASELINE.toPx()
    val corner = CornerRadius(ChartDefaults.BAR_GAP.toPx() / 2)

    values.forEachIndexed { index, value ->
        val barHeight = (stats.barFraction(value) * size.height).coerceAtLeast(baseline)
        drawRoundRect(
            color = if (index == stats.peakIndex) peak else fill,
            // Centred in its slot, so the first and last bars are inset from the card's edges
            // by half a gap rather than sitting flush against them.
            topLeft = Offset(x = slot * index + (slot - width) / 2, y = size.height - barHeight),
            size = Size(width = width, height = barHeight),
            cornerRadius = corner,
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Bar chart", widthDp = 360)
@Composable
fun BarSeriesChartPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        BarSeriesChartCatalogue()
    }
}

@Preview(name = "Bar chart, dark", widthDp = 360)
@Composable
fun BarSeriesChartDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        BarSeriesChartCatalogue()
    }
}

@Composable
private fun BarSeriesChartCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        BarSeriesChart(entries = previewPeaked(), label = "Volume per session")
        BarSeriesChart(entries = previewFlat(), label = "Sessions per week")
        BarSeriesChart(entries = previewZeroed(), label = "Volume per session")
        BarSeriesChart(entries = previewSinglePoint(), label = "Volume per session")
    }
}
