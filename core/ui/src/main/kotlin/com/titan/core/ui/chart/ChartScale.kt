package com.titan.core.ui.chart

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Which band a chart measures against, and where its marks sit in the space it was given.
 *
 * ```text
 *  LINE                          BAR
 *  95 ┤          ╭──●            16,800 ┤        █
 *     │     ╭────╯                      │  ▓  ▓  █  ▓
 *  82 ┤─────╯                         0 ┼──────────────
 * ```
 *
 * [LineSeriesChart] is read against itself — the band runs from the series' own floor to its
 * ceiling, so 90 → 92 kg is a visible rise — and [BarSeriesChart] is read against zero, because
 * bars are quantities standing side by side. [ChartStats] has held that difference since #144;
 * what this adds is a **name** for it, and the reason is the value axis.
 *
 * A rail of figures down the left of a plot has to be labelled with the band the plot was
 * actually drawn against: a line's `82 · 88 · 95` and a bar's `0 · 8,400 · 16,800` are the same
 * component reading two different scales. The tooltip needs the same knowledge one step
 * further on — which mark a thumb landed on, and where that mark is — so the geometry lives
 * here too, in [ChartGeometry], and the charts draw from it rather than from arithmetic of
 * their own. One place to change, and the axis, the tooltip and the drawing cannot drift apart.
 *
 * It is a parameter of [ChartPlot] rather than something inferred from the chart, because the
 * plot is the thing that has to know: see the note there on why the chart stopped being a slot.
 */
enum class ChartScale {

    /** Scaled to the series' own minimum and maximum. See [LineSeriesChart]. */
    LINE,

    /** Scaled from zero to the series' maximum. See [BarSeriesChart]. */
    BAR,
    ;

    /**
     * How far the drawing is held off the edges of its canvas, on every side.
     *
     * A line's first and last points are circles centred on the band's edge, so half of each
     * would be clipped by a canvas drawn to the millimetre — the inset is that radius. Bars
     * have no such overhang: they stand on the bottom edge and are measured from it, and an
     * inset would be a baseline floating above the axis it is meant to be.
     */
    internal val inset: Dp
        get() = when (this) {
            LINE -> ChartDefaults.POINT_RADIUS
            BAR -> 0.dp
        }

    /** Where [value] sits in this scale's band, `0f..1f` measured from the bottom. */
    internal fun fraction(
        stats: ChartStats,
        value: Float,
    ): Float =
        when (this) {
            LINE -> stats.lineFraction(value)
            BAR -> stats.barFraction(value)
        }

    /** The value the bottom of the band stands for: the series' floor, or zero. */
    internal fun floor(stats: ChartStats): Float =
        when (this) {
            LINE -> stats.min
            BAR -> 0f
        }
}

/**
 * A plot's geometry: the canvas it was given, and where each mark of the series falls on it.
 *
 * Built by the drawing code, by the value axis and by the tooltip from the same three inputs,
 * so a gridline, a bubble's leader and the point it is pointing at are the same pixel by
 * construction rather than by three call sites agreeing.
 *
 * @param count how many marks the series holds. At least two — every caller has already been
 *   past [isPlottable], which is what makes [x]'s division safe.
 * @param size the canvas, in pixels.
 * @param inset [ChartScale.inset] in pixels. Taken rather than converted because the two
 *   callers hold a `Density` in different ways — a `DrawScope` is one, a `MeasureScope` has one.
 * @param layoutDirection which way the series runs. A chart's horizontal axis is time, and time
 *   is read in the direction the language is — so under a mirrored layout the newest mark is on
 *   the left. Taken as a parameter rather than read from a composition local because three of
 *   the four callers are not composable: a `DrawScope` carries one, a `MeasureScope` carries
 *   one, and the tap detector has to be handed the value its composable read.
 *
 *   It is **not** defaulted. The month labels under the plot are an ordinary `Row` and mirror
 *   themselves; a geometry that quietly stayed left-to-right would leave `APR` over the oldest
 *   mark and every tap one bar out, which is worse than a chart that does not mirror at all.
 */
internal class ChartGeometry(
    private val scale: ChartScale,
    private val stats: ChartStats,
    private val count: Int,
    val size: Size,
    private val inset: Float,
    private val layoutDirection: LayoutDirection,
) {

    /** The canvas less its insets — the rectangle the series is actually drawn in. */
    val plot: Size = Size(
        width = size.width - inset * 2f,
        height = size.height - inset * 2f,
    )

    /** Whether there is any room left to draw in once the insets are taken off. */
    val isDrawable: Boolean = plot.width > 0f && plot.height > 0f

    /** The width one bar and its gap share. Meaningless for a line, which has no slots. */
    val slot: Float = size.width / count

    /** The single highest mark, or null where nothing is uniquely highest — [ChartStats]. */
    val peakIndex: Int? = stats.peakIndex

    /** The gap between two adjacent marks on a line. */
    private val step: Float = plot.width / (count - 1)

    /** Where mark [index] sits horizontally: a point on the line, the centre of a bar. */
    fun x(index: Int): Float =
        readingOrder(
            when (scale) {
                ChartScale.LINE -> inset + step * index
                ChartScale.BAR -> slot * index + slot / 2f
            },
        )

    /**
     * [x] flipped about the canvas' middle under a mirrored layout, and left alone otherwise.
     *
     * Its own involution, which is why the same call converts both ways: it turns the index
     * arithmetic above into a pixel, and turns a thumb's pixel back into something that
     * arithmetic can be inverted from. `Canvas` and `pointerInput` both work in raw
     * coordinates and mirror nothing on their own.
     */
    private fun readingOrder(x: Float): Float = if (layoutDirection == LayoutDirection.Rtl) size.width - x else x

    /** Where [value] sits vertically. Fractions run from the bottom, canvases from the top. */
    fun y(value: Float): Float = yAt(scale.fraction(stats, value))

    /** Where a fraction of the band sits vertically — a gridline's line, a tick's label. */
    fun yAt(fraction: Float): Float = bandY(fraction = fraction, height = size.height, inset = inset)

    /**
     * The mark nearest [x], for a thumb that landed between two of them.
     *
     * Nearest rather than "inside", because a line's marks have no width to be inside of and a
     * tap two pixels past the last bar is still a tap on the last bar. The result is always a
     * real index: a chart that can be touched at all has at least two marks to offer.
     */
    fun nearestIndex(x: Float): Int =
        readingOrder(x).let { mark ->
            when (scale) {
                ChartScale.LINE -> ((mark - inset) / step).roundToInt()
                ChartScale.BAR -> (mark / slot).toInt()
            }
        }.coerceIn(0, count - 1)
}

/**
 * Where a fraction of the band sits in a canvas [height] pixels tall, held off both edges by
 * [inset].
 *
 * The one line of arithmetic behind a gridline, a tick label and every plotted mark. It is a
 * free function rather than a method because the gridlines are drawn without a series in hand
 * — they are ruled across the card whatever the marks turn out to be — and a [ChartGeometry]
 * built to answer one question about a chart it has no data for would be a fiction.
 */
internal fun bandY(
    fraction: Float,
    height: Float,
    inset: Float,
): Float = inset + (1f - fraction) * (height - inset * 2f)
