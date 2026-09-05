package com.opengym.core.domain.random

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production [RandomSource].
 *
 * Every drop-table test substitutes this, which leaves the real implementation — the one that
 * actually decides what a user pulls — as the single reading nothing checks. The same gap
 * `ClockTest` closes, and for the same reason: a wrong expression here would be silent. A source
 * stuck on zero drops the first entry of every band forever, and a source that ignored its bound
 * would index outside a pool only for the users whose catalogue was small enough.
 */
class RandomSourceTest {

    @Test
    fun `every reading falls inside the bound it was given`() {
        repeat(DRAWS) {
            val reading = RandomSource.SYSTEM.nextInt(BOUND)

            assertTrue("$reading is not in 0 until $BOUND", reading in 0 until BOUND)
        }
    }

    @Test
    fun `a bound of one leaves only one answer`() {
        repeat(DRAWS) {
            assertEquals(0, RandomSource.SYSTEM.nextInt(1))
        }
    }

    @Test
    fun `the readings actually vary`() {
        // The property a constant would pass everything else without: over this many draws of a
        // hundred values, a source that always answered the same thing is not chance.
        val seen = List(DRAWS) { RandomSource.SYSTEM.nextInt(BOUND) }.toSet()

        assertTrue("${seen.size} distinct readings over $DRAWS draws", seen.size > 1)
    }

    @Test
    fun `a bound of zero is refused rather than answered`() {
        // An empty pool the caller should not have asked about: any answer here is an index into
        // nothing, so the seam's contract is to throw. See [RandomSource.nextInt].
        assertThrows(IllegalArgumentException::class.java) { RandomSource.SYSTEM.nextInt(0) }
    }

    private companion object {
        const val BOUND = 100
        const val DRAWS = 200
    }
}
