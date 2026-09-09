package com.opengym.core.ui.chart

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a chart's marks fall, and which mark a thumb lands on — in both reading directions.
 *
 * The manifest has claimed `supportsRtl` since the first commit, and this is the one place in
 * the app where that claim could not be honoured by the layout system on its own: `Canvas` and
 * `pointerInput` work in raw pixels and mirror nothing, while the month labels under the plot
 * are an ordinary `Row` and mirror themselves. Left alone, a mirrored chart drew its newest
 * mark on the right with `APR` printed under the oldest one, and every tap landed on the mark
 * reflected across the middle of the card.
 *
 * Asserted as arithmetic rather than as a screenshot because that is what the defect was:
 * three call sites agreeing about which way time runs. `ChartTooltipTest` covers the same
 * ground from the other end — a real tap through a real composition — in the direction the app
 * currently ships in.
 */
class ChartGeometryTest {

    private val size = Size(width = 300f, height = 100f)

    /** Four marks, so the spacing divides evenly and an off-by-one has somewhere to show. */
    private val entries = listOf(1f, 2f, 3f, 4f)

    @Test
    fun `a series runs left to right, and its marks are mirrors of each other`() {
        val ltr = geometry(ChartScale.BAR, LayoutDirection.Ltr)
        val rtl = geometry(ChartScale.BAR, LayoutDirection.Rtl)

        entries.indices.forEach { index ->
            assertEquals(
                "mark $index should be reflected across the middle of the plot",
                size.width - ltr.x(index),
                rtl.x(index),
                TOLERANCE,
            )
        }
    }

    @Test
    fun `the newest mark of a mirrored line sits at the start of the reading order`() {
        val ltr = geometry(ChartScale.LINE, LayoutDirection.Ltr)
        val rtl = geometry(ChartScale.LINE, LayoutDirection.Rtl)

        // A line is inset by the emphasised point's radius, so "flush" means flush to the inset
        // — which is exactly the arithmetic a hand-rolled `size.width - x` at the call site got
        // wrong before the geometry owned it.
        assertEquals(INSET, ltr.x(0), TOLERANCE)
        assertEquals(size.width - INSET, rtl.x(0), TOLERANCE)
        assertEquals(size.width - INSET, ltr.x(entries.lastIndex), TOLERANCE)
        assertEquals(INSET, rtl.x(entries.lastIndex), TOLERANCE)
    }

    @Test
    fun `a thumb lands on the mark it was put on, whichever way the layout runs`() {
        listOf(ChartScale.LINE, ChartScale.BAR).forEach { scale ->
            listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
                val geometry = geometry(scale, direction)

                entries.indices.forEach { index ->
                    assertEquals(
                        "$scale in $direction: a tap on mark $index resolved elsewhere",
                        index,
                        geometry.nearestIndex(geometry.x(index)),
                    )
                }
            }
        }
    }

    @Test
    fun `the vertical band is untouched by the reading direction`() {
        // Only the horizontal axis is a reading order. Up is up in every language, and a value
        // rail that flipped with the layout would put the ceiling on the floor.
        val ltr = geometry(ChartScale.BAR, LayoutDirection.Ltr)
        val rtl = geometry(ChartScale.BAR, LayoutDirection.Rtl)

        entries.forEach { value -> assertEquals(ltr.y(value), rtl.y(value), TOLERANCE) }
    }

    private fun geometry(
        scale: ChartScale,
        layoutDirection: LayoutDirection,
    ): ChartGeometry =
        ChartGeometry(
            scale = scale,
            stats = ChartStats(entries.mapIndexed { index, value -> chartEntry(index, value) }),
            count = entries.size,
            size = size,
            inset = if (scale == ChartScale.LINE) INSET else 0f,
            layoutDirection = layoutDirection,
        )

    private fun chartEntry(
        index: Int,
        value: Float,
    ): ChartEntry =
        ChartEntry(
            value = value,
            label = "m$index",
            formattedValue = value.toString(),
        )

    private companion object {
        /** A line's inset, in the pixels the geometry works in. Any positive value will do. */
        const val INSET = 6f
        const val TOLERANCE = 0.001f
    }
}
