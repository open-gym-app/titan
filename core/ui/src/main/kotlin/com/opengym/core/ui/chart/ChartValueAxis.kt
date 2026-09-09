package com.opengym.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import kotlin.math.roundToInt

/**
 * The figures a plot is read against: the top of the band, the bottom, and the level between.
 *
 * ### Why the chart could not go on without one
 *
 * #154 and #102 shipped these cards with a month axis and no value axis, which makes a shape
 * that cannot be read: a line climbing across a card says a lifter is going up, and nothing on
 * the card says up *from what to what*. The three figures are the smallest thing that answers
 * it — the band's ends and its middle — and they are the reason [ChartScale] exists, because
 * the bottom of a line's band is the series' own floor while the bottom of a bar's is zero.
 *
 * ### Three, and sometimes one
 *
 * Three at 10sp fit an 72dp plot with air between them, and a fourth starts to read as a table
 * rather than a scale. A **flat** series has no band to divide — a rail reading `80 · 80 · 80`
 * beside a line that never moved is three restatements of one fact — so it gets the single
 * figure it has, at the level the line is actually drawn at. A series of nothing but zeroes is
 * the same case on the bar side, and lands on the baseline where the bars are.
 *
 * @param format the caller's own formatter, because this layer must not format a weight: the
 *   same rail draws kilograms and pounds without knowing which it holds, for the reason
 *   [ChartEntry] carries a formatted string beside its value.
 * @return the ticks from the top down, or empty for a series too short to plot — which draws
 *   no plot either, and a rail of figures beside [ChartPlaceholder] would be a scale for a
 *   chart that is not there.
 */
internal fun chartValueTicks(
    entries: List<ChartEntry>,
    scale: ChartScale,
    format: (Float) -> String,
): List<ChartValueTick> {
    if (!entries.isPlottable()) return emptyList()

    val stats = ChartStats(entries)
    val top = stats.max
    val bottom = scale.floor(stats)

    if (top - bottom <= FLAT_BAND) {
        return listOf(ChartValueTick(text = format(top), fraction = scale.fraction(stats, top)))
    }

    return TICK_FRACTIONS.map { fraction ->
        ChartValueTick(text = format(bottom + (top - bottom) * fraction), fraction = fraction)
    }
}

/**
 * The rail: [chartValueTicks]' figures, each one level with the height it names.
 *
 * Laid out by hand rather than as a `SpaceBetween` column because "level with" is the whole
 * point — an evenly spaced column puts the top label's *top* on the top gridline, half a line
 * of text above the value it claims to name, and the error is worst exactly where the reader
 * is checking the ceiling. Each label is centred on its own tick and clamped to the plot, so
 * the outermost two sit flush rather than hanging off the ends.
 *
 * The rail takes the width of its widest figure and no more, which is why it is measured
 * against nothing: `92.5` costs a quarter of what `108,400` does, and a fixed gutter would
 * spend the larger on every card.
 *
 * Hidden from screen readers in one gesture, like the month labels under the plot — the chart
 * already announces its own range and extremes, and a rail read aloud is three bare numbers
 * with no chart between them. See [chartDescription].
 */
@Composable
internal fun ChartValueRail(
    ticks: List<ChartValueTick>,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    if (ticks.isEmpty()) return

    val color = MaterialTheme.colorScheme.outline

    Layout(
        content = { ticks.forEach { tick -> Eyebrow(text = tick.text, color = color) } },
        modifier = modifier.height(height).clearAndSetSemantics { },
    ) { measurables, constraints ->
        // Unbounded, so a figure reports the width it wants rather than the width it was
        // offered — the rail is sized from them, not the other way round.
        val labels = measurables.map { it.measure(Constraints()) }
        val railWidth = labels.maxOf { it.width }
        val railHeight = constraints.maxHeight

        layout(width = railWidth, height = railHeight) {
            labels.forEachIndexed { index, label ->
                val y = bandY(
                    fraction = ticks[index].fraction,
                    height = railHeight.toFloat(),
                    inset = 0f,
                ) - label.height / 2f
                label.placeRelative(
                    // Flush against the plot the figures belong to, and the decimal points of a
                    // mono face then line up without a decimal-aligned layout. `placeRelative`
                    // rather than `place` so "against the plot" survives a mirrored layout,
                    // which `place` would leave meaning "on the right" in both.
                    x = railWidth - label.width,
                    y = y.roundToInt().coerceIn(0, (railHeight - label.height).coerceAtLeast(0)),
                )
            }
        }
    }
}

/**
 * The lines the rail's figures are read along, ruled across the plot behind the series.
 *
 * A figure at the edge of a card and a mark in the middle of one are a centimetre apart, and
 * without a rule between them the reader is estimating rather than reading. Dashed and in the
 * soft rule, so the series stays the thing on the card that is drawn solid — the same
 * distinction between data and chrome that [ChartDefaults.LINE_WIDTH] makes for the line.
 *
 * Drawn from [bandY] against [ChartScale.inset], which is what keeps a line's top gridline on
 * the line's own ceiling rather than three pixels above it.
 */
@Composable
internal fun ChartGridLines(
    ticks: List<ChartValueTick>,
    scale: ChartScale,
    modifier: Modifier = Modifier,
) {
    if (ticks.isEmpty()) return

    val color = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val inset = scale.inset.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))

        ticks.forEach { tick ->
            val y = bandY(fraction = tick.fraction, height = size.height, inset = inset)
            drawLine(
                color = color,
                start = Offset(x = 0f, y = y),
                end = Offset(x = size.width, y = y),
                strokeWidth = HAIRLINE_WIDTH.toPx(),
                pathEffect = dash,
            )
        }
    }
}

/**
 * Where the ticks sit, from the top down: the ceiling, the middle and the floor of the band.
 *
 * The order is the order they are drawn in, which is also the order they are read in.
 */
private val TICK_FRACTIONS = listOf(1f, 0.5f, 0f)

/**
 * How close the ends of a band have to be before it is one level rather than a range.
 *
 * The same reasoning as `ChartStats.FLAT_TOLERANCE`, and deliberately the same magnitude: a
 * band the drawing calls flat must not be a band the rail divides into three, or the middle
 * tick names a height no mark is ever drawn at.
 */
private const val FLAT_BAND = 1e-6f

/** The dash and the gap of a gridline. Short enough to read as a rule rather than a series. */
private val DASH_ON: Dp = HAIRLINE_WIDTH * 2f
private val DASH_OFF: Dp = HAIRLINE_WIDTH * 3f
