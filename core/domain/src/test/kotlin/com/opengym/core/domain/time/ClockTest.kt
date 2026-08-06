package com.opengym.core.domain.time

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production [Clock].
 *
 * Every lifecycle test pins its own time by substituting this, which leaves the real
 * implementation — the one that actually stamps rows — as the single reading nothing
 * checks. It is one expression, but the wrong one would be silent: a monotonic source
 * such as `System.nanoTime()` also counts up and also compares sensibly against itself,
 * and would only surface as nonsense timestamps in the user's history.
 */
class ClockTest {

    @Test
    fun `the system clock reads epoch milliseconds`() {
        val before = System.currentTimeMillis()
        val reading = Clock.SYSTEM.nowMillis()
        val after = System.currentTimeMillis()

        assertTrue("$reading is not a wall-clock reading taken between $before and $after", reading in before..after)
    }

    @Test
    fun `the system clock does not run backwards between two readings`() {
        val first = Clock.SYSTEM.nowMillis()
        val second = Clock.SYSTEM.nowMillis()

        assertTrue("$second precedes $first", second >= first)
    }
}
