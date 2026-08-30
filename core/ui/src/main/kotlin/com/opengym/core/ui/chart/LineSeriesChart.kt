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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A series as a line, with the area under it filled and the latest point emphasised.
 *
 * ```text
 *                                    ●
 *                          ●──●──●───
 *          ●───●──●───●────
 *  ●───●───
 * ▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒
 * ```
 *
 * The stats hub's cumulative-volume hero, the exercise screen's one-rep-max estimate, the
 * body-weight trend and Home's eight-week card are all this component. It owns the scale;
 * callers supply values and labels — see [ChartEntry].
 *
 * ### Scaled to the series, not to zero
 *
 * A line is read for its direction, so the band runs from the series' own minimum to its
 * maximum: a lifter going 4,000 → 4,200 kg a week should see a rise rather than four bars of
 * nearly identical height. That is the opposite decision from [BarSeriesChart], which
 * measures quantities against zero, and [ChartStats] holds both so neither chart has to
 * choose twice.
 *
 * ### One accent, and it is the latest point
 *
 * The line, the fill and every point are the accent's family, but the *emphasised* mark is
 * the last one — where the user is now, which is what a trend is read to find out. The peak
 * is [BarSeriesChart]'s emphasis, not this one's: on a line the highest point is already the
 * top of the drawing.
 *
 * ### Fewer than two points draws nothing
 *
 * See [ChartPlaceholder]. A one-point line has no direction, and a zeroed line across an
 * empty card claims the user trained and lifted nothing.
 *
 * @param label what the series is, for the description a screen reader is given —
 *   `Cumulative volume`. Not drawn: the card around the chart carries its own eyebrow.
 * @param height how tall the plot is. The width is whatever the caller gives it.
 */
@Composable
fun LineSeriesChart(
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
    val stroke = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primaryContainer

    Canvas(
        // One node for the whole plot. Without this the individual marks are announced one by
        // one, which is twelve swipes to reach the figure the card is about.
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        drawLineSeries(stats = stats, values = entries.map { it.value }, stroke = stroke, fill = fill)
    }
}

/**
 * The plot itself.
 *
 * Inset by the emphasised point's radius on every side, so the first and last marks sit inside
 * the canvas rather than being clipped in half by it — a line whose newest point is cut down
 * the middle reads as a chart that ran out of room.
 */
private fun DrawScope.drawLineSeries(
    stats: ChartStats,
    values: List<Float>,
    stroke: Color,
    fill: Color,
) {
    val inset = ChartDefaults.POINT_RADIUS.toPx()
    val plot = Size(width = size.width - inset * 2, height = size.height - inset * 2)
    if (plot.width <= 0f || plot.height <= 0f) return

    val step = plot.width / (values.size - 1)
    val points = values.mapIndexed { index, value ->
        Offset(
            x = inset + step * index,
            // Fractions are measured from the bottom and the canvas from the top.
            y = inset + (1f - stats.lineFraction(value)) * plot.height,
        )
    }

    val line = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    // The same path closed down to the baseline. Built from the line rather than beside it, so
    // the fill cannot drift a pixel from the edge it is meant to sit under.
    val area = Path().apply {
        addPath(line)
        lineTo(points.last().x, size.height)
        lineTo(points.first().x, size.height)
        close()
    }

    drawPath(path = area, color = fill)
    drawPath(path = line, color = stroke, style = Stroke(width = ChartDefaults.LINE_WIDTH.toPx()))

    points.dropLast(1).forEach { point ->
        drawCircle(color = stroke, radius = ChartDefaults.MINOR_POINT_RADIUS.toPx(), center = point)
    }
    drawCircle(color = stroke, radius = ChartDefaults.POINT_RADIUS.toPx(), center = points.last())
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Line chart", widthDp = 360)
@Composable
fun LineSeriesChartPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        LineSeriesChartCatalogue()
    }
}

@Preview(name = "Line chart, dark", widthDp = 360)
@Composable
fun LineSeriesChartDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        LineSeriesChartCatalogue()
    }
}

@Composable
private fun LineSeriesChartCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        LineSeriesChart(entries = previewRising(), label = "Cumulative volume")
        LineSeriesChart(entries = previewFlat(), label = "Body weight")
        LineSeriesChart(entries = previewSinglePoint(), label = "One-rep max estimate")
        LineSeriesChart(entries = emptyList(), label = "Cumulative volume")
    }
}
