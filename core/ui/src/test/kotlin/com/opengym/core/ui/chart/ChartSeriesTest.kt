package com.opengym.core.ui.chart

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the charts, tested without composing one.
 *
 * The three cases #144 names — a flat series, a single-point series and an empty one — are all
 * decided here rather than in the drawing code, so this is where the divisions by zero either
 * exist or do not. What a chart *looks* like is the screenshot references' job; what it
 * computes is this file's.
 */
class ChartSeriesTest {

    // --- scaling ------------------------------------------------------------

    @Test
    fun `a line is scaled against the series own floor and ceiling`() {
        val stats = ChartStats(series(10f, 20f, 30f))

        assertEquals(0f, stats.lineFraction(10f), TOLERANCE)
        assertEquals(0.5f, stats.lineFraction(20f), TOLERANCE)
        assertEquals(1f, stats.lineFraction(30f), TOLERANCE)
    }

    @Test
    fun `a bar is scaled against zero, so a near-equal pair does not read as a stub`() {
        val stats = ChartStats(series(10f, 20f, 30f))

        // The smallest bar is a third of the tallest, not nothing — which is what scaling
        // against the series' own minimum, as the line does, would draw it as.
        assertEquals(1f / 3f, stats.barFraction(10f), TOLERANCE)
        assertEquals(1f, stats.barFraction(30f), TOLERANCE)
    }

    @Test
    fun `a flat series draws down the middle rather than dividing by a zero range`() {
        val stats = ChartStats(series(80f, 80f, 80f, 80f))

        assertTrue(stats.isFlat)
        assertEquals(0.5f, stats.lineFraction(80f), TOLERANCE)
        // Bars measure from zero, so a flat series is a row of full ones — which is true.
        assertEquals(1f, stats.barFraction(80f), TOLERANCE)
    }

    @Test
    fun `a series of nothing but zeroes fills nothing and divides by nothing`() {
        val stats = ChartStats(series(0f, 0f, 0f))

        assertEquals(0f, stats.barFraction(0f), TOLERANCE)
        assertEquals(0.5f, stats.lineFraction(0f), TOLERANCE)
    }

    @Test
    fun `an empty series has no scale to speak of and does not throw asking for one`() {
        val stats = ChartStats(emptyList())

        assertEquals(0f, stats.min, TOLERANCE)
        assertEquals(0f, stats.max, TOLERANCE)
        assertEquals(0f, stats.barFraction(0f), TOLERANCE)
    }

    @Test
    fun `a negative value is clamped to the baseline rather than drawn through it`() {
        val stats = ChartStats(series(0f, 10f))

        assertEquals(0f, stats.barFraction(-5f), TOLERANCE)
        assertEquals(0f, stats.lineFraction(-5f), TOLERANCE)
    }

    // --- the peak -----------------------------------------------------------

    @Test
    fun `the peak is the single highest mark`() {
        assertEquals(3, ChartStats(series(8f, 10f, 9f, 16f, 11f)).peakIndex)
    }

    @Test
    fun `a tie for the top accents nothing, because the accent means this one`() {
        assertNull(ChartStats(series(8f, 16f, 9f, 16f)).peakIndex)
    }

    @Test
    fun `a flat series has no peak`() {
        val stats = ChartStats(series(12f, 12f, 12f))

        assertTrue(stats.isFlat)
        assertNull(stats.peakIndex)
    }

    @Test
    fun `an average that differs in the sixth decimal is still one level`() {
        // Days per week, computed as a quotient: two periods that trained identically must not
        // report a rise because the division landed a bit apart.
        assertTrue(ChartStats(series(3.2f, 3.2000005f)).isFlat)
    }

    // --- how much is enough to plot -----------------------------------------

    @Test
    fun `two marks are a trend and one is not`() {
        assertFalse(emptyList<ChartEntry>().isPlottable())
        assertFalse(series(92f).isPlottable())
        assertTrue(series(88f, 92f).isPlottable())
    }

    // --- the axis -----------------------------------------------------------

    @Test
    fun `repeated labels collapse into one run carrying the width it covers`() {
        val labels = chartAxisLabels(
            listOf(
                entry(1f, "Feb"),
                entry(2f, "Feb"),
                entry(3f, "Mar"),
                entry(4f, "Apr"),
            ),
        )

        assertEquals(listOf("Feb", "Mar", "Apr"), labels.map { it.text })
        assertEquals(listOf(2f, 1f, 1f), labels.map { it.weight })
    }

    @Test
    fun `too many runs are blanked rather than dropped, so the survivors stay over their marks`() {
        val labels = chartAxisLabels((1..6).map { entry(it.toFloat(), "W$it") })

        // Six runs, three drawn: the first, the last, and the one in between. Every run is
        // still present and still one mark wide, which is what keeps `W6` above the sixth mark
        // rather than a third of the way along.
        assertEquals(6, labels.size)
        assertEquals(listOf(1f, 1f, 1f, 1f, 1f, 1f), labels.map { it.weight })
        assertEquals(listOf("W1", "", "", "W4", "", "W6"), labels.map { it.text })
    }

    @Test
    fun `a series too short to plot has no axis either`() {
        assertTrue(chartAxisLabels(series(92f)).isEmpty())
        assertTrue(chartAxisLabels(emptyList()).isEmpty())
    }

    // --- the value axis ----------------------------------------------------

    @Test
    fun `a line is labelled with its own floor, its ceiling and the level between them`() {
        val ticks = chartValueTicks(series(80f, 100f, 90f), ChartScale.LINE) { "%.0f".format(it) }

        // Top down, and the fractions are the ones the line is drawn against — the middle tick
        // names 90, which is where a 90 kg mark sits on a band running 80 to 100.
        assertEquals(listOf("100", "90", "80"), ticks.map { it.text })
        assertEquals(listOf(1f, 0.5f, 0f), ticks.map { it.fraction })
    }

    @Test
    fun `bars are labelled from zero, because that is what they are measured against`() {
        val ticks = chartValueTicks(series(80f, 100f, 90f), ChartScale.BAR) { "%.0f".format(it) }

        assertEquals(listOf("100", "50", "0"), ticks.map { it.text })
    }

    @Test
    fun `a flat line is one figure at the level it is drawn at, not three of the same`() {
        val ticks = chartValueTicks(series(80f, 80f, 80f), ChartScale.LINE) { "%.0f".format(it) }

        assertEquals(listOf("80"), ticks.map { it.text })
        // Where `ChartStats.lineFraction` puts a flat line: halfway, because it went neither way.
        assertEquals(0.5f, ticks.single().fraction, TOLERANCE)
    }

    @Test
    fun `a fortnight of nothing is one zero on the baseline, and divides by nothing`() {
        val ticks = chartValueTicks(series(0f, 0f), ChartScale.BAR) { "%.0f".format(it) }

        assertEquals(listOf("0"), ticks.map { it.text })
        assertEquals(0f, ticks.single().fraction, TOLERANCE)
    }

    @Test
    fun `a series too short to plot has no value axis either`() {
        assertTrue(chartValueTicks(series(92f), ChartScale.LINE) { "" }.isEmpty())
        assertTrue(chartValueTicks(emptyList(), ChartScale.BAR) { "" }.isEmpty())
    }

    // --- where the marks are -----------------------------------------------

    @Test
    fun `a line spans the plot, inset by the radius of the points at either end`() {
        val geometry = geometry(ChartScale.LINE, series(0f, 50f, 100f), width = 220f)

        // 200 of usable width across two gaps, held off both edges by a 10px inset.
        assertEquals(10f, geometry.x(0), TOLERANCE)
        assertEquals(110f, geometry.x(1), TOLERANCE)
        assertEquals(210f, geometry.x(2), TOLERANCE)
        // The band is inset by the same amount, so the ceiling is not drawn off the top.
        assertEquals(10f, geometry.y(100f), TOLERANCE)
        assertEquals(90f, geometry.y(0f), TOLERANCE)
    }

    @Test
    fun `a bar sits in the middle of its own slot, and the series spans the full width`() {
        val geometry = geometry(ChartScale.BAR, series(0f, 50f, 100f), width = 300f)

        assertEquals(50f, geometry.x(0), TOLERANCE)
        assertEquals(150f, geometry.x(1), TOLERANCE)
        assertEquals(250f, geometry.x(2), TOLERANCE)
        // Measured from zero and from the very bottom: bars stand on the edge of the canvas.
        assertEquals(0f, geometry.y(100f), TOLERANCE)
        assertEquals(100f, geometry.y(0f), TOLERANCE)
    }

    @Test
    fun `a tap lands on the nearest mark, and past the ends on the ends`() {
        val geometry = geometry(ChartScale.BAR, series(1f, 2f, 3f, 4f), width = 400f)

        assertEquals(0, geometry.nearestIndex(0f))
        assertEquals(1, geometry.nearestIndex(150f))
        assertEquals(3, geometry.nearestIndex(399f))
        // A thumb is wider than a bar and a canvas rounds: a tap a pixel off either end is
        // still a tap on the chart, and clamping is what stops it being an index nobody holds.
        assertEquals(0, geometry.nearestIndex(-20f))
        assertEquals(3, geometry.nearestIndex(480f))
    }

    private fun geometry(
        scale: ChartScale,
        entries: List<ChartEntry>,
        width: Float,
        height: Float = 100f,
        inset: Float = if (scale == ChartScale.LINE) 10f else 0f,
    ) = ChartGeometry(
        scale = scale,
        stats = ChartStats(entries),
        count = entries.size,
        size = Size(width = width, height = height),
        inset = inset,
        // The arithmetic below is about where marks fall in a band, which is the same question
        // in every language. The mirroring is `ChartGeometryTest`'s.
        layoutDirection = LayoutDirection.Ltr,
    )

    private fun series(vararg values: Float): List<ChartEntry> =
        values.mapIndexed { index, value -> entry(value, "W$index") }

    private fun entry(
        value: Float,
        label: String,
    ) = ChartEntry(value = value, label = label, formattedValue = "$value kg")

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
