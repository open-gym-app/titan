@file:OptIn(ExperimentalCoroutinesApi::class)

package com.opengym.core.domain.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/*
 * Shared rig for the rest timer tests.
 *
 * The countdown is driven entirely by virtual time: the controller sleeps on the test
 * scheduler and *measures* on `testScheduler.timeSource`, so neither can run ahead of
 * the other and a 90-second rest costs the suite no wall-clock time at all.
 */

/**
 * A controller whose countdown and clock both run on the test scheduler.
 *
 * The countdown belongs to [TestScope.backgroundScope], so a test that deliberately
 * leaves a timer running still finishes instead of hanging on an un-awaited child.
 */
fun TestScope.restTimer(tick: Duration = RestTimerController.DEFAULT_TICK) =
    RestTimerController(
        scope = backgroundScope,
        timeSource = testScheduler.timeSource,
        tick = tick,
    )

/**
 * Everything a controller has published, with the virtual instant of each publication.
 *
 * The instants are captured here rather than read off `testScheduler.currentTime` after
 * the fact, because [runOutTheClock] deliberately overshoots every deadline — "the timer
 * finished at 90s" is a fact about when the state arrived, not about where the clock
 * was left.
 */
class RestTimerRecording {

    private val published = mutableListOf<Pair<RestTimerState, Long>>()

    /** Every state published so far, oldest first. */
    val states: List<RestTimerState> get() = published.map { it.first }

    /** The instants, in virtual milliseconds, at which states matching [predicate] were published. */
    fun instantsOf(predicate: (RestTimerState) -> Boolean): List<Long> =
        published.filter { predicate(it.first) }.map { it.second }

    /** The instant [state] was first published, in virtual milliseconds. */
    fun instantOf(state: RestTimerState): Long =
        instantsOf { it == state }.firstOrNull()
            ?: throw AssertionError("$state was never published; saw $states")

    internal fun add(
        state: RestTimerState,
        atMillis: Long,
    ) {
        published += state to atMillis
    }
}

/**
 * Starts recording what [controller] publishes and returns the live [RestTimerRecording] —
 * read it after the transitions under test, not before.
 *
 * Collection runs on an [UnconfinedTestDispatcher] so each state is recorded at the
 * instant it is published. A collector on the default dispatcher would let the
 * conflating [kotlinx.coroutines.flow.StateFlow] drop intermediate values, which is
 * exactly what these tests are asserting on.
 */
fun TestScope.record(controller: RestTimerController): RestTimerRecording {
    val recording = RestTimerRecording()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        controller.state.collect { recording.add(it, testScheduler.currentTime) }
    }
    return recording
}

/**
 * Runs virtual time out well past any deadline these tests set up, so a started
 * countdown reaches its end.
 *
 * Not `advanceUntilIdle`: neither the [TestScope] nor the [kotlinx.coroutines.test.TestCoroutineScheduler]
 * form of it advances time for work owned by [TestScope.backgroundScope] — they stop as
 * soon as the foreground is idle, on the grounds that background work may never end — and
 * the countdown lives there. `advanceTimeBy` does run it, which is why every deadline
 * assertion reads its instant off a [RestTimerRecording] instead of off the clock.
 */
fun TestScope.runOutTheClock() {
    testScheduler.advanceTimeBy(1.hours)
}

/**
 * Advances virtual time so exactly [offset] has passed since the test began, running
 * everything scheduled up to and including that instant.
 *
 * `advanceTimeBy` alone would stop *before* a task scheduled at the target — which is
 * precisely the tick these tests care about — and overshooting by a millisecond to pull
 * it in would make every remaining-duration assertion off by that millisecond. Landing
 * on the instant and then draining it with `runCurrent` keeps both exact.
 */
fun TestScope.advanceTimeTo(offset: Duration) {
    val remaining = offset.inWholeMilliseconds - testScheduler.currentTime
    require(remaining >= 0) { "cannot rewind virtual time to $offset" }
    testScheduler.advanceTimeBy(remaining)
    testScheduler.runCurrent()
}
