@file:OptIn(ExperimentalCoroutinesApi::class)

package com.titan.core.domain.timer

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
    fun `the timer starts idle`() =
        runTest {
            val timer = restTimer()

            assertEquals(RestTimerState.Idle, timer.state.value)
        }

    @Test
    fun `starting publishes running with the full duration before it returns`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)

            assertEquals(RestTimerState.Running(90.seconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `a paused and resumed rest walks idle running paused running finished`() =
        runTest {
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
    fun `cancel returns to idle without finishing`() =
        runTest {
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
    fun `cancel stops the ticks`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.start(3.seconds)
            timer.cancel()
            val publishedByCancel = recording.states.size
            runOutTheClock()

            assertEquals(publishedByCancel, recording.states.size)
        }

    @Test
    fun `cancelling a paused rest returns to idle`() =
        runTest {
            val timer = restTimer()

            timer.start(3.seconds)
            advanceTimeTo(1.seconds)
            timer.pause()
            timer.cancel()
            runOutTheClock()

            assertEquals(RestTimerState.Idle, timer.state.value)
        }

    @Test
    fun `a resumed rest keeps the original total`() =
        runTest {
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
    fun `starting again replaces the running countdown`() =
        runTest {
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
    fun `starting again from paused restarts rather than resumes`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(10.seconds)
            timer.pause()
            timer.start(30.seconds)

            assertEquals(RestTimerState.Running(30.seconds, 30.seconds), timer.state.value)
        }

    @Test
    fun `a finished rest stays finished`() =
        runTest {
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
    fun `pause is a no-op when the timer is not running`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.pause()

            assertEquals(listOf(RestTimerState.Idle), recording.states)
        }

    @Test
    fun `resume is a no-op when the timer is not paused`() =
        runTest {
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
    fun `cancel is a no-op when the timer is already idle`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.cancel()

            assertEquals(listOf(RestTimerState.Idle), recording.states)
        }

    @Test
    fun `resuming a rest with nothing left finishes it rather than running a zero countdown`() =
        runTest {
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

    // ─── Adjusting a rest in flight ─────────────────────────────────────────────────────

    @Test
    fun `extending adds to the remainder and keeps the total`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(30.seconds)
            timer.adjust(15.seconds)

            // 60 left of 90 became 75 left of 90: the bar goes back by the fifteen seconds that
            // were added and by nothing else, which is the whole point of keeping the total.
            assertEquals(RestTimerState.Running(75.seconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `shortening takes off the remainder and keeps the total`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(30.seconds)
            timer.adjust((-15).seconds)

            assertEquals(RestTimerState.Running(45.seconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `an adjusted rest finishes at the moved deadline`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.start(90.seconds)
            advanceTimeTo(30.seconds)
            timer.adjust(15.seconds)
            runOutTheClock()

            // 30s in, 75s left: 105s from the start rather than the 90s it was started with.
            assertEquals(105_000L, recording.instantOf(RestTimerState.Finished))
        }

    @Test
    fun `adjusting takes the fraction of a second since the last tick with it`() =
        runTest {
            // The reason this is the controller's job rather than the screen's: half a second past
            // the tick, the published state still says 60s and the truth is 59.5s. An adjustment
            // computed from what was published would hand the user back the half second.
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(30.seconds + 500.milliseconds)
            timer.adjust(15.seconds)

            assertEquals(RestTimerState.Running(74.5.seconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `extending past the total grows the total to the new remainder`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(5.seconds)
            timer.adjust(15.seconds)

            // 85 + 15 is 100, which no `Running` of 90 can hold. The total is the thing that
            // gives, and by exactly as much as it has to.
            assertEquals(RestTimerState.Running(100.seconds, 100.seconds), timer.state.value)
        }

    @Test
    fun `shortening past zero finishes the rest rather than leaving it running`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.start(90.seconds)
            advanceTimeTo(80.seconds)
            timer.adjust((-15).seconds)

            // Finished, not Idle: the user asked for the rest to be over, which is a rest that
            // ran out early rather than one that was called off.
            assertEquals(RestTimerState.Finished, timer.state.value)
            assertEquals(80_000L, recording.instantOf(RestTimerState.Finished))

            // And the countdown it cut short publishes nothing further, so the ten seconds it
            // still had do not tick away underneath a finished rest.
            runOutTheClock()
            assertEquals(RestTimerState.Finished, recording.states.last())
            assertEquals(1, recording.instantsOf { it == RestTimerState.Finished }.size)
        }

    @Test
    fun `adjusting a paused rest leaves it paused`() =
        runTest {
            val timer = restTimer()

            timer.start(90.seconds)
            advanceTimeTo(30.seconds)
            timer.pause()
            timer.adjust(15.seconds)
            advanceTimeTo(60.seconds) // parked: an adjustment is not a resume

            assertEquals(RestTimerState.Paused(75.seconds, 90.seconds), timer.state.value)
        }

    @Test
    fun `shortening a paused rest past zero finishes it`() =
        runTest {
            val timer = restTimer()

            timer.restore(RestTimerState.Paused(10.seconds, 90.seconds))
            timer.adjust((-15).seconds)

            assertEquals(RestTimerState.Finished, timer.state.value)
        }

    @Test
    fun `adjust is a no-op when there is no rest to adjust`() =
        runTest {
            val timer = restTimer()
            val recording = record(timer)

            timer.adjust(15.seconds)
            timer.restore(RestTimerState.Finished)
            timer.adjust(15.seconds)

            assertEquals(listOf(RestTimerState.Idle, RestTimerState.Finished), recording.states)
        }

    @Test
    fun `a non-positive rest duration is rejected`() =
        runTest {
            val timer = restTimer()

            val thrown = assertThrows(IllegalArgumentException::class.java) { timer.start(Duration.ZERO) }

            assertEquals("a rest duration must be positive, was 0s", thrown.message)
            assertEquals(RestTimerState.Idle, timer.state.value)
        }

    @Test
    fun `a non-positive tick interval is rejected`() =
        runTest {
            assertThrows(IllegalArgumentException::class.java) { restTimer(tick = Duration.ZERO) }
        }
}
