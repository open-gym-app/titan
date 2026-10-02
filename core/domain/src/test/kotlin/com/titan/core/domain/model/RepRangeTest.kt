package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ranges the goals target, and the pairs the type refuses at the door. */
class RepRangeTest {

    @Test
    fun `the three named goals target the textbook ranges`() {
        // Product decisions a suggestion will show to every user, pinned as numbers.
        assertEquals(RepRange(3, 6), RepRange.STRENGTH)
        assertEquals(RepRange(8, 12), RepRange.HYPERTROPHY)
        assertEquals(RepRange(15, 20), RepRange.ENDURANCE)
        assertEquals(RepRange.HYPERTROPHY, RepRange.DEFAULT_CUSTOM)
    }

    @Test
    fun `both ends are inside the range`() {
        val range = RepRange(6, 10)

        assertTrue(6 in range)
        assertTrue(10 in range)
        assertFalse(5 in range)
        assertFalse(11 in range)
    }

    @Test
    fun `a single rep count is a range of one`() {
        assertTrue(5 in RepRange(5, 5))
    }

    @Test
    fun `a range that ends before it starts is refused`() {
        assertThrows(IllegalArgumentException::class.java) { RepRange(10, 6) }
    }

    @Test
    fun `a range starting under one rep is refused`() {
        assertThrows(IllegalArgumentException::class.java) { RepRange(0, 6) }
    }

    @Test
    fun `a range ending past the ceiling is refused`() {
        assertThrows(IllegalArgumentException::class.java) { RepRange(6, RepRange.MAX_REPS + 1) }
        RepRange(6, RepRange.MAX_REPS)
    }
}
