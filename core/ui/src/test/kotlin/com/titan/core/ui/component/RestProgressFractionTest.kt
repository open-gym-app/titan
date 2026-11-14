package com.titan.core.ui.component

import com.titan.core.domain.timer.RestTimerState
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How much of a rest the progress bar draws as gone, without a composition to draw it in.
 *
 * The bar's own rendering is `RestTimerSheetTest`'s; this pins the arithmetic, and above all
 * that no state the timer can be restored into makes it throw.
 */
class RestProgressFractionTest {

    @Test
    fun `a running rest is the share of its total already rested`() {
        assertEquals(0.25f, RestTimerState.Running(remaining = 90.seconds, total = 120.seconds).elapsedFraction())
    }

    @Test
    fun `finished is a full bar and idle an empty one`() {
        assertEquals(1f, RestTimerState.Finished.elapsedFraction())
        assertEquals(0f, RestTimerState.Idle.elapsedFraction())
    }

    /**
     * A workout draft written while a restored backup's rest was running reopens with both
     * figures infinite, and `∞ − ∞` threw on every recomposition of the sheet (#256).
     */
    @Test
    fun `an infinite rest is an empty bar rather than a crash`() {
        assertEquals(
            0f,
            RestTimerState.Paused(remaining = Duration.INFINITE, total = Duration.INFINITE).elapsedFraction(),
        )
        assertEquals(
            0f,
            RestTimerState.Running(remaining = Duration.INFINITE, total = Duration.INFINITE).elapsedFraction(),
        )
        assertEquals(0f, RestTimerState.Paused(remaining = 30.seconds, total = Duration.INFINITE).elapsedFraction())
    }
}
