package com.titan.core.ui.chart

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.HAIRLINE_WIDTH
import com.titan.core.ui.theme.TitanTheme

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
 * ### The touched point, when there is one
 *
 * [selectedIndex] is the mark a thumb landed on — [ChartPlot] resolves the tap and owns the
 * bubble that says what it is worth; this draws the half of it that is part of the plot. The
 * point is ringed in the card's own surface so it reads as picked out rather than merely
 * larger, and a leader runs from it up to the tooltip, which is what keeps a bubble that had
 * to slide sideways attached to the mark it names.
 *
 * @param label what the series is, for the description a screen reader is given —
 *   `Cumulative volume`. Not drawn: the card around the chart carries its own eyebrow.
 * @param height how tall the plot is. The width is whatever the caller gives it.
 * @param selectedIndex the mark to pick out, or null for a chart nobody has touched.
 */
@Composable
fun LineSeriesChart(
    entries: List<ChartEntry>,
    label: String,
    modifier: Modifier = Modifier,
    height: Dp = ChartDefaults.HEIGHT,
    selectedIndex: Int? = null,
) {
    val spoken = chartDescription(label = label, entries = entries)
    if (!entries.isPlottable()) {
        ChartPlaceholder(label = label, modifier = modifier, height = height)
        return
    }

    val stats = ChartStats(entries)
    val stroke = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primaryContainer
    val surface = MaterialTheme.colorScheme.surface
    val guide = MaterialTheme.colorScheme.outline

    Canvas(
        // One node for the whole plot. Without this the individual marks are announced one by
        // one, which is twelve swipes to reach the figure the card is about.
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        val geometry = ChartGeometry(
            scale = ChartScale.LINE,
            stats = stats,
            count = entries.size,
            size = size,
            inset = ChartScale.LINE.inset.toPx(),
            layoutDirection = layoutDirection,
        )
        drawLineSeries(
            geometry = geometry,
            values = entries.map { it.value },
            stroke = stroke,
            fill = fill,
            surface = surface,
            guide = guide,
            selectedIndex = selectedIndex?.takeIf { it in entries.indices },
        )
    }
}

/**
 * The plot itself.
 *
 * Inset by the emphasised point's radius on every side, so the first and last marks sit inside
 * the canvas rather than being clipped in half by it — a line whose newest point is cut down
 * the middle reads as a chart that ran out of room. Where the marks fall inside that inset is
 * [ChartGeometry]'s answer rather than this function's, so the tooltip points at the pixel the
 * point was drawn on.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawLineSeries(
    geometry: ChartGeometry,
    values: List<Float>,
    stroke: Color,
    fill: Color,
    surface: Color,
    guide: Color,
    selectedIndex: Int?,
) {
    if (!geometry.isDrawable) return

    val points = values.mapIndexed { index, value -> Offset(x = geometry.x(index), y = geometry.y(value)) }

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

    selectedIndex?.let { index ->
        drawSelectedPoint(point = points[index], stroke = stroke, surface = surface, guide = guide)
    }
}

/**
 * The touched point, and the leader running up to its tooltip.
 *
 * Drawn over the line rather than under it, and ringed in the surface the card is painted on,
 * so the mark reads as lifted off a series that runs behind it. The leader stops at the point:
 * a rule the full height of the plot would be a second gridline in the accent, competing with
 * the value axis it crosses.
 */
private fun DrawScope.drawSelectedPoint(
    point: Offset,
    stroke: Color,
    surface: Color,
    guide: Color,
) {
    drawLine(
        color = guide,
        start = Offset(x = point.x, y = 0f),
        end = point,
        strokeWidth = HAIRLINE_WIDTH.toPx(),
    )
    val radius = ChartDefaults.SELECTED_POINT_RADIUS.toPx()
    drawCircle(color = surface, radius = radius + ChartDefaults.LINE_WIDTH.toPx(), center = point)
    drawCircle(color = stroke, radius = radius, center = point)
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Line chart", widthDp = 360)
@Composable
fun LineSeriesChartPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        LineSeriesChartCatalogue()
    }
}

@Preview(name = "Line chart, dark", widthDp = 360)
@Composable
fun LineSeriesChartDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        LineSeriesChartCatalogue()
    }
}

@Composable
private fun LineSeriesChartCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        LineSeriesChart(entries = previewRising(), label = "Cumulative volume")
        LineSeriesChart(entries = previewFlat(), label = "Body weight")
        LineSeriesChart(entries = previewSinglePoint(), label = "One-rep max estimate")
        LineSeriesChart(entries = emptyList(), label = "Cumulative volume")
    }
}
