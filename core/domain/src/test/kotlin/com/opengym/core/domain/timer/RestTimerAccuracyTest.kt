@file:OptIn(ExperimentalCoroutinesApi::class)

package com.opengym.core.domain.timer

import com.opengym.core.domain.model.UserPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the countdown does to the clock: when each tick lands, when the rest is over,
 * and how much time a pause is holding.
 *
 * The interesting failure these guard against is drift. A `delay(1.seconds)` loop is
 * correct on paper and loses a little on every iteration in practice, and the loss is
 * invisible in a three-second test — so the cadence assertions run the full 90 seconds
 * and compare against absolute instants, not against the previous tick.
 *
 * The state machine itself is covered in [RestTimerControllerTest].
 */
class RestTimerAccuracyTest {

    @Test
    fun `a running rest ticks once a second`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.start(5.seconds)
            runOutTheClock()

            assertEquals(
                listOf(
                    RestTimerState.Idle,
                    RestTimerState.Running(5.seconds, 5.seconds),
                    RestTimerState.Running(4.seconds, 5.seconds),
                    RestTimerState.Running(3.seconds, 5.seconds),
                    RestTimerState.Running(2.seconds, 5.seconds),
                    RestTimerState.Running(1.seconds, 5.seconds),
                    RestTimerState.Finished,
                ),
                recording.states,
            )
        }

    @Test
    fun `a default rest finishes ninety seconds after it started`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)
            val startedAt = testScheduler.currentTime

            timer.start(UserPreferences.DEFAULT_REST_DURATION)
            runOutTheClock()

            assertEquals(RestTimerState.Finished, timer.state.value)
            val elapsed = (recording.instantOf(RestTimerState.Finished) - startedAt).milliseconds
            assertTrue(
                "a 90s rest finished after $elapsed, outside the +/-1s tolerance",
                (elapsed - 90.seconds).absoluteValue <= 1.seconds,
            )
            assertEquals(90.seconds, elapsed) // deadline-based, so in fact exact
        }

    @Test
    fun `a ninety second rest counts every second down exactly once`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.start(UserPreferences.DEFAULT_REST_DURATION)
            runOutTheClock()

            val remaining = recording.states.filterIsInstance<RestTimerState.Running>().map { it.remaining }
            assertEquals((90 downTo 1).map { it.seconds }, remaining)
        }

    @Test
    fun `ticks do not drift over a ninety second run`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)
            val startedAt = testScheduler.currentTime

            timer.start(UserPreferences.DEFAULT_REST_DURATION)
            runOutTheClock()

            assertEquals(
                (0 until 90).map { startedAt + it * 1_000L },
                recording.instantsOf { it is RestTimerState.Running },
            )
        }

    @Test
    fun `pause freezes the exact remainder rather than the last published tick`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(1_500.milliseconds)
            timer.pause()

            assertEquals(RestTimerState.Paused(88_500.milliseconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `a paused rest consumes no time`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(30.seconds)
            timer.pause()
            advanceTimeTo(10.minutes)

            assertEquals(RestTimerState.Paused(60.seconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `resume continues from the exact paused value`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(1_500.milliseconds)
            timer.pause()
            advanceTimeTo(1.minutes)
            timer.resume()

            assertEquals(RestTimerState.Running(88_500.milliseconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `a resumed rest finishes exactly its remainder later`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.start(90.seconds)
            advanceTimeTo(1_500.milliseconds)
            timer.pause()
            advanceTimeTo(1.minutes)
            val resumedAt = testScheduler.currentTime
            timer.resume()
            runOutTheClock()

            assertEquals(RestTimerState.Finished, timer.state.value)
            assertEquals(88_500L, recording.instantOf(RestTimerState.Finished) - resumedAt)
        }

    @Test
    fun `a rest shorter than one tick still finishes on its deadline`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)
            val startedAt = testScheduler.currentTime

            timer.start(400.milliseconds)
            runOutTheClock()

            assertEquals(
                listOf(
                    RestTimerState.Idle,
                    RestTimerState.Running(400.milliseconds, 400.milliseconds),
                    RestTimerState.Finished,
                ),
                recording.states,
            )
            assertEquals(400L, recording.instantOf(RestTimerState.Finished) - startedAt)
        }

    @Test
    fun `a rest that is not a whole number of ticks finishes on its deadline`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)
            val startedAt = testScheduler.currentTime

            timer.start(2_500.milliseconds)
            runOutTheClock()

            assertEquals(RestTimerState.Finished, timer.state.value)
            assertEquals(2_500L, recording.instantOf(RestTimerState.Finished) - startedAt)
            assertEquals(
                listOf(2_500.milliseconds, 1_500.milliseconds, 500.milliseconds),
                recording.states.filterIsInstance<RestTimerState.Running>().map { it.remaining },
            )
        }

    @Test
    fun `the tick interval is configurable`() =
        runTest {
            val timer = restTimer(tick = 5.seconds)
            val recording = record(timer)

            timer.start(15.seconds)
            runOutTheClock()

            assertEquals(
                listOf(
                    RestTimerState.Idle,
                    RestTimerState.Running(15.seconds, 15.seconds),
                    RestTimerState.Running(10.seconds, 15.seconds),
                    RestTimerState.Running(5.seconds, 15.seconds),
                    RestTimerState.Finished,
                ),
                recording.states,
            )
        }
}
