@file:OptIn(ExperimentalCoroutinesApi::class)

package com.opengym.core.domain.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The state machine: which command lands in which state, and which ones do nothing.
 *
 * Sequences are asserted as whole lists rather than one `awaitItem` at a time, so a
 * transition that published an extra state — a stale tick from a countdown that was
 * meant to be dead, most of all — fails here instead of passing unnoticed.
 *
 * Timing behaviour lives in [RestTimerAccuracyTest]; the rig is in `RestTimerTestSupport`.
 */
class RestTimerControllerTest {

    @Test
    fun `the timer starts idle`() = runTest {
        val timer = restTimer()

        assertEquals(RestTimerState.Idle, timer.state.value)
    }

    @Test
    fun `starting publishes running with the full duration before it returns`() = runTest {
        val timer = restTimer()

        timer.start(90.seconds)

        assertEquals(RestTimerState.Running(90.seconds, 90.seconds), timer.state.value)
    }

    @Test
    fun `a paused and resumed rest walks idle running paused running finished`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(3.seconds)
        advanceTimeTo(1.seconds)
        timer.pause()
        advanceTimeTo(5.seconds) // parked: the countdown must not consume any of this
        timer.resume()
        runOutTheClock()

        assertEquals(
            listOf(
                RestTimerState.Idle,
                RestTimerState.Running(3.seconds, 3.seconds),
                RestTimerState.Running(2.seconds, 3.seconds),
                RestTimerState.Paused(2.seconds, 3.seconds),
                RestTimerState.Running(2.seconds, 3.seconds),
                RestTimerState.Running(1.seconds, 3.seconds),
                RestTimerState.Finished,
            ),
            recording.states,
        )
    }

    @Test
    fun `cancel returns to idle without finishing`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(3.seconds)
        advanceTimeTo(1.seconds)
        timer.cancel()
        runOutTheClock()

        assertEquals(RestTimerState.Idle, timer.state.value)
        assertTrue("cancel must not publish Finished", RestTimerState.Finished !in recording.states)
    }

    @Test
    fun `cancel stops the ticks`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(3.seconds)
        timer.cancel()
        val publishedByCancel = recording.states.size
        runOutTheClock()

        assertEquals(publishedByCancel, recording.states.size)
    }

    @Test
    fun `cancelling a paused rest returns to idle`() = runTest {
        val timer = restTimer()

        timer.start(3.seconds)
        advanceTimeTo(1.seconds)
        timer.pause()
        timer.cancel()
        runOutTheClock()

        assertEquals(RestTimerState.Idle, timer.state.value)
    }

    @Test
    fun `a resumed rest keeps the original total`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(90.seconds)
        advanceTimeTo(1_500.milliseconds)
        timer.pause()
        timer.resume()
        runOutTheClock()

        val totals = recording.states.mapNotNull {
            when (it) {
                is RestTimerState.Running -> it.total
                is RestTimerState.Paused -> it.total
                else -> null
            }
        }
        assertEquals(setOf(90.seconds), totals.toSet())
    }

    @Test
    fun `starting again replaces the running countdown`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(90.seconds)
        advanceTimeTo(10.seconds)
        val restartedAt = testScheduler.currentTime
        timer.start(30.seconds)
        runOutTheClock()

        assertEquals(RestTimerState.Finished, timer.state.value)
        assertEquals(30_000L, recording.instantOf(RestTimerState.Finished) - restartedAt)
        assertEquals(1, recording.states.count { it is RestTimerState.Finished })
    }

    @Test
    fun `starting again from paused restarts rather than resumes`() = runTest {
        val timer = restTimer()

        timer.start(90.seconds)
        advanceTimeTo(10.seconds)
        timer.pause()
        timer.start(30.seconds)

        assertEquals(RestTimerState.Running(30.seconds, 30.seconds), timer.state.value)
    }

    @Test
    fun `a finished rest stays finished`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.start(2.seconds)
        runOutTheClock()
        timer.pause()
        timer.resume()
        runOutTheClock()

        assertEquals(RestTimerState.Finished, timer.state.value)
        assertEquals(1, recording.states.count { it is RestTimerState.Finished })
    }

    @Test
    fun `pause is a no-op when the timer is not running`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.pause()

        assertEquals(listOf(RestTimerState.Idle), recording.states)
    }

    @Test
    fun `resume is a no-op when the timer is not paused`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.resume()
        timer.start(3.seconds)
        timer.resume()
        advanceTimeTo(1.seconds)

        assertEquals(
            listOf(
                RestTimerState.Idle,
                RestTimerState.Running(3.seconds, 3.seconds),
                RestTimerState.Running(2.seconds, 3.seconds),
            ),
            recording.states,
        )
    }

    @Test
    fun `cancel is a no-op when the timer is already idle`() = runTest {
        val timer = restTimer()
        val recording = record(timer)

        timer.cancel()

        assertEquals(listOf(RestTimerState.Idle), recording.states)
    }

    @Test
    fun `resuming a rest with nothing left finishes it rather than running a zero countdown`() = runTest {
        // The edge `resume` documents and nothing reached until now: a rest paused in the
        // instant between its countdown expiring and `Finished` being published. There is
        // no Running state that could express it — the type rejects a non-positive
        // remainder — so resuming has to land straight on Finished.
        val timer = restTimer()
        timer.restore(RestTimerState.Paused(Duration.ZERO, 90.seconds))
        val recording = record(timer)

        timer.resume()
        advanceTimeTo(5.seconds)

        assertEquals(
            listOf(RestTimerState.Paused(Duration.ZERO, 90.seconds), RestTimerState.Finished),
            recording.states,
        )
    }

    @Test
    fun `a non-positive rest duration is rejected`() = runTest {
        val timer = restTimer()

        val thrown = assertThrows(IllegalArgumentException::class.java) { timer.start(Duration.ZERO) }

        assertEquals("a rest duration must be positive, was 0s", thrown.message)
        assertEquals(RestTimerState.Idle, timer.state.value)
    }

    @Test
    fun `a non-positive tick interval is rejected`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { restTimer(tick = Duration.ZERO) }
    }
}
