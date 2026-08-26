@file:OptIn(ExperimentalCoroutinesApi::class)

package com.opengym.core.domain.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val NOW: Long = 1_700_000_000_000L

/**
 * The persisted rest timer: a deadline going in, a remainder coming out.
 *
 * The case that matters most is the one a literal round trip would get wrong — a draft
 * written with 45 seconds left and read ten minutes later must restore a rest that is
 * *over*, not one still counting down 45 seconds. Everything else here exists to pin
 * the arithmetic around that.
 *
 * The last two tests close the loop by handing the result to a real
 * [RestTimerController], which is what a restoring caller actually does.
 */
class RestTimerSnapshotTest {

    @Test
    fun `a running rest is persisted as its deadline, not its remainder`() {
        val snapshot = RestTimerSnapshot.of(RestTimerState.Running(45.seconds, 90.seconds), NOW)

        assertEquals(RestTimerSnapshot.Running(deadline = NOW + 45_000, total = 90.seconds), snapshot)
    }

    @Test
    fun `a deadline still in the future restores the time actually left`() {
        val snapshot = RestTimerSnapshot.Running(deadline = NOW + 90_000, total = 90.seconds)

        assertEquals(RestTimerState.Running(30.seconds, 90.seconds), snapshot.toStateAt(NOW + 60_000))
    }

    @Test
    fun `a deadline that has passed restores as finished, not as a rest with time left`() {
        val snapshot = RestTimerSnapshot.of(RestTimerState.Running(45.seconds, 90.seconds), NOW)

        assertEquals(RestTimerState.Finished, snapshot.toStateAt(NOW + 10.minutes.inWholeMilliseconds))
    }

    @Test
    fun `a deadline one millisecond away still restores as running`() {
        val snapshot = RestTimerSnapshot.Running(deadline = NOW + 90_000, total = 90.seconds)

        assertEquals(RestTimerState.Running(1.milliseconds, 90.seconds), snapshot.toStateAt(NOW + 89_999))
    }

    @Test
    fun `a deadline reached to the millisecond restores as finished`() {
        val snapshot = RestTimerSnapshot.Running(deadline = NOW + 90_000, total = 90.seconds)

        assertEquals(RestTimerState.Finished, snapshot.toStateAt(NOW + 90_000))
    }

    @Test
    fun `a rest read back immediately keeps its remainder`() {
        val running = RestTimerState.Running(45.seconds, 90.seconds)

        assertEquals(running, RestTimerSnapshot.of(running, NOW).toStateAt(NOW))
    }

    @Test
    fun `a clock that stepped backwards cannot restore more than the total`() {
        val snapshot = RestTimerSnapshot.of(RestTimerState.Running(90.seconds, 90.seconds), NOW)

        assertEquals(RestTimerState.Running(90.seconds, 90.seconds), snapshot.toStateAt(NOW - 30_000))
    }

    @Test
    fun `a paused rest is persisted as its remainder, which does not go stale`() {
        val paused = RestTimerState.Paused(45.seconds, 90.seconds)

        val snapshot = RestTimerSnapshot.of(paused, NOW)

        assertEquals(RestTimerSnapshot.Paused(45.seconds, 90.seconds), snapshot)
        assertEquals(paused, snapshot.toStateAt(NOW + 10.minutes.inWholeMilliseconds))
    }

    @Test
    fun `idle and finished survive whatever the clock says`() {
        val later = NOW + 10.minutes.inWholeMilliseconds

        assertEquals(RestTimerState.Idle, RestTimerSnapshot.of(RestTimerState.Idle, NOW).toStateAt(later))
        assertEquals(RestTimerState.Finished, RestTimerSnapshot.of(RestTimerState.Finished, NOW).toStateAt(later))
    }

    @Test
    fun `a running rest is persisted to whole milliseconds`() {
        val snapshot = RestTimerSnapshot.of(RestTimerState.Running(45_000_500.microseconds, 90.seconds), NOW)

        assertEquals(RestTimerSnapshot.Running(deadline = NOW + 45_000, total = 90.seconds), snapshot)
    }

    @Test
    fun `a persisted rest with no total is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestTimerSnapshot.Running(deadline = NOW, total = Duration.ZERO)
        }
    }

    @Test
    fun `a persisted pause that owes time or exceeds its total is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            RestTimerSnapshot.Paused(remaining = (-1).seconds, total = 90.seconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RestTimerSnapshot.Paused(remaining = 91.seconds, total = 90.seconds)
        }
    }

    @Test
    fun `a restored rest counts down the time that was actually left`() =
        runTest {
            val timer = restTimer()
            val snapshot = RestTimerSnapshot.of(RestTimerState.Running(90.seconds, 90.seconds), NOW)
            val recording = record(timer)
            val restoredAt = testScheduler.currentTime

            timer.restore(snapshot.toStateAt(NOW + 30_000))
            runOutTheClock()

            assertEquals(60_000L, recording.instantOf(RestTimerState.Finished) - restoredAt)
        }

    @Test
    fun `a rest that elapsed while the process was gone never starts counting`() =
        runTest {
            val timer = restTimer()
            val snapshot = RestTimerSnapshot.of(RestTimerState.Running(90.seconds, 90.seconds), NOW)
            val recording = record(timer)

            timer.restore(snapshot.toStateAt(NOW + 10.minutes.inWholeMilliseconds))
            runOutTheClock()

            assertEquals(RestTimerState.Finished, timer.state.value)
            assertEquals(emptyList<RestTimerState>(), recording.states.filterIsInstance<RestTimerState.Running>())
        }
}
