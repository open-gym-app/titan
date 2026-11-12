package com.titan.core.domain.model

import com.titan.core.domain.validation.SetValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [paceOf] at the edges of what `SetValidator` admits, and past them. */
class PaceTest {

    @Test
    fun `a pace is nanoseconds per centimetre, rounded half up`() {
        // 5 km in 25 minutes: 1,500 s over 500,000 cm is 3,000,000 ns/cm.
        assertEquals(3_000_000L, paceOf(1_500, 500_000))
    }

    @Test
    fun `the longest duration over the shortest distance still fits`() {
        assertEquals(SetValidator.DURATION_MAX_SECONDS * NANOS_PER_SECOND, paceOf(SetValidator.DURATION_MAX_SECONDS, 1))
    }

    /** `duration × 10⁹` wrapped for a duration past `Long.MAX_VALUE / 10⁹` (#256). */
    @Test
    fun `a duration or a distance past the validator's bounds is no pace`() {
        assertNull(paceOf(Long.MAX_VALUE, 500_000))
        assertNull(paceOf(SetValidator.DURATION_MAX_SECONDS + 1, 500_000))
        assertNull(paceOf(1_500, SetValidator.DISTANCE_MAX_CM + 1))
    }

    @Test
    fun `a missing or non-positive figure is no pace`() {
        assertNull(paceOf(null, 500_000))
        assertNull(paceOf(1_500, 0))
    }
}
