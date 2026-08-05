@file:OptIn(ExperimentalCoroutinesApi::class)

package com.opengym.core.domain.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Taking over a rest that was already in flight — the seam a layer that can outlive the
 * process uses to hand one back after a restart.
 *
 * These tests stand in for the caller described on [RestTimerController.restore]: it
 * persisted a deadline, read it back against a sleep-counting clock, worked out what was
 * left, and arrives here with a state. What is asserted is that the controller then
 * behaves as though it had been counting all along — the remainder is honoured to the
 * millisecond, the total survives, and a restored rest that is already over does not
 * start counting.
 *
 * The rest of the state machine is in [RestTimerControllerTest]; timing in
 * [RestTimerAccuracyTest].
 */
class RestTimerRestoreTest {

    @Test
    fun `restoring a running rest continues from the persisted remainder`() = runTest {
        val timer = restTimer()

        timer.restore(RestTimerState.Running(60.seconds, 90.seconds))

        assertEquals(RestTimerState.Running(60.seconds, 90.seconds), timer.state.value)
    }

    @Test
    fun `a restored rest finishes exactly its remainder later`() = runTest {
        val timer = restTimer()
        val recording = record(timer)
        val restoredAt = testScheduler.currentTime

        timer.restore(RestTimerState.Running(60.seconds, 90.seconds))
        runOutTheClock()

        assertEquals(RestTimerState.Finished, timer.state.value)
        assertEquals(60_000L, recording.instantOf(RestTimerState.Finished) - restoredAt)
    }

    @Test
    fun `a restored rest honours a sub-second remainder`() = runTest {
        val timer = restTimer()
        val recording = record(timer)
        val restoredAt = testScheduler.currentTime

        timer.restore(RestTimerState.Running(2_400.milliseconds, 90.seconds))
        runOutTheClock()

        assertEquals(2_400L, recording.instantOf(RestTimerState.Finished) - restoredAt)
        assertEquals(
            listOf(2_400.milliseconds, 1_400.milliseconds, 400.milliseconds),
            recording.states.filterIsInstance<RestTimerState.Running>().map { it.remaining },
        )
    }

    @Test
    fun `a restored rest keeps the original total`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.restore(RestTimerState.Running(3.seconds, 90.seconds))
        runOutTheClock()

        assertEquals(
            listOf(
                RestTimerState.Idle,
                RestTimerState.Running(3.seconds, 90.seconds),
                RestTimerState.Running(2.seconds, 90.seconds),
                RestTimerState.Running(1.seconds, 90.seconds),
                RestTimerState.Finished,
            ),
            recording.states,
        )
    }

    @Test
    fun `a restored rest can still be paused and resumed`() = runTest {
        val timer = restTimer()

        timer.restore(RestTimerState.Running(60.seconds, 90.seconds))
        advanceTimeTo(1_500.milliseconds)
        timer.pause()

        assertEquals(RestTimerState.Paused(58_500.milliseconds, 90.seconds), timer.state.value)
    }

    @Test
    fun `restoring a paused rest does not start counting`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.restore(RestTimerState.Paused(60.seconds, 90.seconds))
        runOutTheClock()

        assertEquals(RestTimerState.Paused(60.seconds, 90.seconds), timer.state.value)
        assertEquals(
            listOf(RestTimerState.Idle, RestTimerState.Paused(60.seconds, 90.seconds)),
            recording.states,
        )
    }

    @Test
    fun `a restored paused rest resumes from its remainder`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.restore(RestTimerState.Paused(60.seconds, 90.seconds))
        advanceTimeTo(10.seconds)
        val resumedAt = testScheduler.currentTime
        timer.resume()
        runOutTheClock()

        assertEquals(60_000L, recording.instantOf(RestTimerState.Finished) - resumedAt)
    }

    @Test
    fun `a rest that elapsed while the process was gone is restored as finished`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.restore(RestTimerState.Finished)
        runOutTheClock()

        assertEquals(RestTimerState.Finished, timer.state.value)
        assertTrue(
            "restoring Finished must not count anything down",
            recording.states.none { it is RestTimerState.Running },
        )
    }

    @Test
    fun `restoring idle abandons a countdown without finishing it`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(90.seconds)
        advanceTimeTo(10.seconds)
        timer.restore(RestTimerState.Idle)
        runOutTheClock()

        assertEquals(RestTimerState.Idle, timer.state.value)
        assertTrue("cancelling via restore must not publish Finished", RestTimerState.Finished !in recording.states)
    }

    @Test
    fun `restoring replaces a countdown that is already running`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(90.seconds)
        advanceTimeTo(10.seconds)
        val restoredAt = testScheduler.currentTime
        timer.restore(RestTimerState.Running(5.seconds, 90.seconds))
        runOutTheClock()

        assertEquals(5_000L, recording.instantOf(RestTimerState.Finished) - restoredAt)
        assertEquals(1, recording.states.count { it is RestTimerState.Finished })
    }

    @Test
    fun `restoring a paused rest stops a countdown that is already running`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(90.seconds)
        advanceTimeTo(10.seconds)
        timer.restore(RestTimerState.Paused(5.seconds, 90.seconds))
        runOutTheClock()

        assertEquals(RestTimerState.Paused(5.seconds, 90.seconds), timer.state.value)
        assertTrue(
            "the replaced countdown must not go on publishing",
            RestTimerState.Finished !in recording.states,
        )
    }
}
