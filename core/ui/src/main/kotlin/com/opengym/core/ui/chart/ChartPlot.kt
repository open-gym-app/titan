package com.opengym.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme
import kotlin.math.roundToInt

/**
 * A plot with both of its axes and a tooltip on it: the figures down the left, the periods
 * along the bottom, and what one mark is worth when it is touched.
 *
 * ```text
 *  95 ┤· · · · · · · ┌──────────┐· · ·
 *     │              │ 12 APR · 92.5│
 *  88 ┤· · · · ·╭──╮ └────┬─────┘· ·
 *     │  ╭──────╯  ╰──────●
 *  82 ┤──╯
 *      FEB       MAR        APR
 * ```
 *
 * ### Why the chart stopped being a slot
 *
 * [ChartCard] used to take the plot as a lambda, so a card could hold whichever chart suited
 * its question without growing a parameter per chart. A value axis ends that: a rail of
 * figures has to be labelled with the band the plot was drawn against, and a tooltip has to
 * know where mark seven sits — neither of which can be asked of an opaque slot. So the chart
 * is named by its [ChartScale] instead, which is the *reason* the two charts differ rather
 * than a flag naming which one to draw, and the rail, the gridlines, the tooltip and the
 * drawing all read the band off it. See [ChartGeometry].
 *
 * ### The rail is opt-in, and the periods are not
 *
 * A chart under a hero figure — the stats hub's cumulative volume — is a shape beside a number
 * that already says what it is worth; a chart that *is* the card has nothing else to be read
 * against. So [formatValue] is nullable, and a caller that leaves it out gets the plot and its
 * month labels exactly as before.
 *
 * ### Selection is hoisted; [ChartCard] is the stateful half
 *
 * The convention CONTRIBUTING.md § Compose asks for, and it buys two things here: a preview
 * can draw the tooltip open, and a screen that wanted two charts to move together could make
 * them. [onSelect] is handed the **new** selection rather than the tapped mark — tapping the
 * mark that is already open closes it, which is the only way to put a tooltip away on a card
 * that is not a dialog.
 *
 * ### A plot nobody is listening to does not take the tap
 *
 * [onSelect] is nullable, and null is what a chart on a card that *navigates* passes — Home's
 * eight-week volume card, whose whole surface opens the stats tab. The tap detector is then not
 * installed at all rather than installed over a callback that discards the tap: two things
 * cannot own one gesture, and a plot that swallowed it would leave the card looking broken
 * while the axis around it still worked.
 *
 * @param label what the series is, for the description the chart hands a screen reader.
 * @param formatValue the caller's own formatter for the rail's figures, without a unit — see
 *   [chartValueTicks]. Null draws no rail and no gridlines.
 * @param selectedIndex which mark the tooltip is open on, or null. An index the series does
 *   not hold is ignored rather than clamped: it means the series changed under the selection,
 *   and pointing at a neighbouring mark would put a wrong figure on screen.
 * @param onSelect the mark a thumb landed on, or **null** for a plot that is not touchable —
 *   see above.
 */
@Composable
fun ChartPlot(
    entries: List<ChartEntry>,
    label: String,
    scale: ChartScale,
    modifier: Modifier = Modifier,
    height: Dp = ChartDefaults.HEIGHT,
    formatValue: ((Float) -> String)? = null,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
) {
    val plottable = entries.isPlottable()
    val ticks = if (formatValue == null) emptyList() else chartValueTicks(entries, scale, formatValue)
    val selected = selectedIndex?.takeIf { plottable && it in entries.indices }

    // Read at the moment of the tap rather than when the detector was installed: keying the
    // gesture on the selection instead would tear the detector down inside its own callback.
    val currentSelection by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)

    // `PointerInputScope` carries a density but no layout direction, so the tap detector is
    // handed the one its composable can see. Without it a mirrored chart would draw its marks
    // one way and resolve thumbs the other.
    val direction = LocalLayoutDirection.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        ChartValueRail(ticks = ticks, height = height)

        // The plot and its period labels sit tighter than the card's own rhythm: the months
        // are part of the drawing rather than a second block under it.
        Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .then(
                        if (plottable && select != null) {
                            Modifier.chartSelection(entries, scale, direction) { index ->
                                select?.invoke(if (index == currentSelection) null else index)
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                ChartGridLines(ticks = ticks, scale = scale, modifier = Modifier.matchParentSize())
                when (scale) {
                    ChartScale.LINE ->
                        LineSeriesChart(entries = entries, label = label, height = height, selectedIndex = selected)

                    ChartScale.BAR ->
                        BarSeriesChart(entries = entries, label = label, height = height, selectedIndex = selected)
                }
                if (selected != null) {
                    ChartTooltip(
                        entries = entries,
                        index = selected,
                        scale = scale,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            ChartAxisLabels(labels = chartAxisLabels(entries))
        }
    }
}

/**
 * Taps on the plot, resolved to the mark nearest the thumb.
 *
 * A tap rather than a scrub, deliberately. These cards live inside a vertically scrolling
 * column, and a chart that tracked every drag would either fight the scroll for the gesture or
 * change its own reading while the screen moved under the finger. A tap contests nothing: the
 * scroll only claims a gesture once it has travelled, and one that never travels was a tap.
 *
 * Nothing is consumed, so a drag that begins on the chart still scrolls the screen.
 */
private fun Modifier.chartSelection(
    entries: List<ChartEntry>,
    scale: ChartScale,
    layoutDirection: LayoutDirection,
    onTap: (Int) -> Unit,
): Modifier =
    pointerInput(entries, scale, layoutDirection) {
        // Built once the plot has a size, which is the only moment the marks have positions.
        val geometry = ChartGeometry(
            scale = scale,
            stats = ChartStats(entries),
            count = entries.size,
            size = Size(size.width.toFloat(), size.height.toFloat()),
            inset = scale.inset.toPx(),
            layoutDirection = layoutDirection,
        )
        detectTapGestures { offset -> onTap(geometry.nearestIndex(offset.x)) }
    }

/**
 * The tooltip, over the mark it is about.
 *
 * Placed by hand because there is nothing to align it to: the mark is a pixel inside a canvas,
 * not a composable. It is centred on that pixel and then clamped into the plot, so a bubble
 * over the first or last mark slides sideways rather than being cut off at the card's edge —
 * the leader line the chart draws is what keeps it attached once it has moved.
 *
 * It sits **above** the mark where there is room and below it where there is not, which on a
 * 72dp plot is the difference between a tooltip on a peak and a tooltip off the top of the
 * card.
 */
@Composable
private fun ChartTooltip(
    entries: List<ChartEntry>,
    index: Int,
    scale: ChartScale,
    modifier: Modifier = Modifier,
) {
    val entry = entries[index]
    val stats = ChartStats(entries)

    Layout(content = { ChartTooltipBubble(entry) }, modifier = modifier) { measurables, constraints ->
        val bubble = measurables.first().measure(Constraints(maxWidth = constraints.maxWidth))
        val geometry = ChartGeometry(
            scale = scale,
            stats = stats,
            count = entries.size,
            size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
            inset = scale.inset.toPx(),
            layoutDirection = layoutDirection,
        )
        val gap = ChartDefaults.TOOLTIP_GAP.toPx()
        val markX = geometry.x(index)
        val markY = geometry.y(entry.value)
        val above = markY - gap - bubble.height

        layout(width = constraints.maxWidth, height = constraints.maxHeight) {
            bubble.place(
                x = (markX - bubble.width / 2f)
                    .roundToInt()
                    .coerceIn(0, (constraints.maxWidth - bubble.width).coerceAtLeast(0)),
                y = (if (above >= 0f) above else markY + gap)
                    .roundToInt()
                    .coerceIn(0, (constraints.maxHeight - bubble.height).coerceAtLeast(0)),
            )
        }
    }
}

/**
 * What the tooltip says: which session, and what it was worth.
 *
 * One line, in the two roles the app already reads a period and a figure in — the eyebrow for
 * the date, the numeric role for the value. Two lines would be a card of its own sitting on a
 * 72dp plot, and there are only ever two things to say.
 *
 * Merged into one node, so a screen reader that reaches it is told `12 Apr, 92.5 kg` rather
 * than a date and a weight two swipes apart.
 */
@Composable
private fun ChartTooltipBubble(entry: ChartEntry) {
    val shape = MaterialTheme.shapes.extraSmall

    Row(
        modifier = Modifier
            .background(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = shape)
            .border(width = HAIRLINE_WIDTH, color = MaterialTheme.colorScheme.outlineVariant, shape = shape)
            .padding(horizontal = OpenGymTheme.spacing.md, vertical = OpenGymTheme.spacing.xs)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(text = entry.tooltipLabel)
        Text(
            text = entry.formattedValue,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Chart plot - a mark touched", widthDp = 360)
@Composable
fun ChartPlotPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ChartPlotCatalogue()
    }
}

@Preview(name = "Chart plot - a mark touched, dark", widthDp = 360)
@Composable
fun ChartPlotDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ChartPlotCatalogue()
    }
}

/**
 * The tooltip open on each chart, which is the one state a screenshot cannot reach by itself:
 * the reference exists so a change to the bubble, its leader or the mark it picks out is a
 * reviewable diff rather than something only a thumb finds.
 *
 * The two selections are chosen to cover the placement rule — a mark low in the band, where
 * the bubble sits above it, and the peak, where there is no room above and it goes below.
 */
@Composable
private fun ChartPlotCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        OpenGymCard {
            ChartPlot(
                entries = previewRising(),
                label = "One-rep max estimate",
                scale = ChartScale.LINE,
                modifier = Modifier.padding(OpenGymTheme.spacing.card),
                height = ChartDefaults.TALL_HEIGHT,
                formatValue = ::previewFormat,
                selectedIndex = 2,
            )
        }
        OpenGymCard {
            ChartPlot(
                entries = previewPeaked(),
                label = "Volume per session",
                scale = ChartScale.BAR,
                modifier = Modifier.padding(OpenGymTheme.spacing.card),
                formatValue = ::previewFormat,
                selectedIndex = 5,
            )
        }
    }
}
