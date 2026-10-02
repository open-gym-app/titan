package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two ratios every weight in the app passes through.
 *
 * Pinned rather than assumed, because they are the definition rather than a calculation: a
 * pound is *defined* as 453.59237 g, so a value that drifts here is not a rounding change, it is
 * the app quietly deciding a pound is something else.
 */
class WeightsTest {

    @Test
    fun `a kilogram is a million milligrams`() {
        assertEquals(1_000_000.0, MILLIGRAMS_PER_KILOGRAM, 0.0)
        assertEquals(MILLIGRAMS_PER_KILOGRAM, UnitSystem.METRIC.milligramsPerUnit, 0.0)
    }

    @Test
    fun `a pound is exactly 453 point 59237 grams`() {
        assertEquals(453_592.37, MILLIGRAMS_PER_POUND, 0.0)
        assertEquals(MILLIGRAMS_PER_POUND, UnitSystem.IMPERIAL.milligramsPerUnit, 0.0)
    }
}
