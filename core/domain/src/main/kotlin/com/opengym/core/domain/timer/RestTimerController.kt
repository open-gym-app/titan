package com.opengym.core.domain.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The rest timer between two sets, as a state machine over [RestTimerState].
 *
 * A ViewModel constructs one of these with its own scope and collects [state]; all the
 * countdown mechanics — when to tick, how much is left, what a pause does to the
 * remainder — live here rather than in a `LaunchedEffect`, so they can be tested
 * without a UI and cannot be reimplemented slightly differently on the next screen.
 *
 * ### What this does not do
 *
 * It does not survive its own process. Everything here is in memory, and the countdown
 * only advances while something is running it, so a rest does not keep time across a
 * kill — nor, on Android, across device sleep, because the default [timeSource] does not
 * count it. Both are deliberately out of this class: an alert that fires while the app is
 * gone is an OS-level concern, and this module may not import one. [restore] is the seam
 * that lets a layer which *can* schedule alarms hand a rest back after a restart, and its
 * documentation carries the contract for persisting the deadline.
 *
 * ### Accuracy
 *
 * The countdown is *deadline-based*, not `delay(1.seconds)` in a loop. Every tick
 * sleeps until the next whole second **measured from the mark taken when the countdown
 * started**, so the ordinary scheduling slop of each individual wake-up is absorbed by
 * the next one instead of accumulating: a 90-second rest finishes 90 seconds after it
 * began, not 90 seconds plus ninety times whatever the dispatcher was late by.
 *
 * [timeSource] is the elapsed-time reading that makes this measurable. It defaults to
 * [TimeSource.Monotonic] — a wall clock is the wrong tool, because a rest interval must
 * not change length when the user crosses a timezone or an NTP sync steps the clock —
 * and a test substitutes `TestCoroutineScheduler.timeSource` so virtual time drives both
 * the sleeping and the measuring.
 *
 * ### Threading
 *
 * The commands are meant to be issued from one thread, the same way a ViewModel
 * issues them from the main dispatcher; they are not synchronized against each other.
 * They *are* safe against the countdown coroutine, which may resume on any thread:
 * [Job.cancel] only requests cancellation, so a tick already past its `delay` could
 * still publish a state that a just-issued command has superseded. Each countdown
 * therefore carries a generation stamp and publishes nothing once that stamp is stale.
 *
 * ### Illegal transitions
 *
 * Unlike the session lifecycle use cases, a command that does not apply — `resume()`
 * on a timer that is not paused, `pause()` on one that is not running — is a no-op
 * rather than an error. The caller here is a button whose enabled-ness is derived from
 * [state]; a race between the render and the tap is not something to report to the
 * user. `start()` always applies, and restarts an already-running countdown.
 */
class RestTimerController(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val tick: Duration = DEFAULT_TICK,
) {

    init {
        require(tick > Duration.ZERO) { "the tick interval must be positive, was $tick" }
    }

    private val _state = MutableStateFlow<RestTimerState>(RestTimerState.Idle)

    /** The current state, re-emitted on every transition and on every tick. Never empty. */
    val state: StateFlow<RestTimerState> = _state.asStateFlow()

    /**
     * Bumped by every command. A countdown compares it against the value it captured and
     * gives up if it has moved, which is what keeps a cancelled tick from publishing.
     */
    private val generation = AtomicLong()

    private var countdown: Job? = null
    private var run: Run? = null

    /**
     * Starts (or restarts) a rest of [duration], publishing [RestTimerState.Running]
     * before returning and a fresh `Running` on every whole second after that.
     *
     * Applies in any state: starting the next set's rest while the previous countdown is
     * still going replaces it, and neither the replaced countdown's remaining ticks nor
     * its `Finished` ever surface.
     *
     * [duration] is required rather than defaulted on purpose. How long a rest lasts is
     * the user's to decide — [com.opengym.core.domain.model.UserPreferences.defaultRestDuration],
     * overridden by an exercise's own `defaultRest` — and a default here would be a second
     * answer to that question, one that a caller who forgot to read the setting would get
     * silently. This class owns the countdown, not the policy.
     */
    fun start(duration: Duration) {
        require(duration > Duration.ZERO) { "a rest duration must be positive, was $duration" }
        countDownFrom(remaining = duration, total = duration)
    }

    /**
     * Freezes a running countdown at the time actually left, which is the true remainder
     * and not the last value published — pausing 1.5s into a 90s rest freezes 88.5s.
     *
     * A no-op unless the timer is running.
     */
    fun pause() {
        if (_state.value !is RestTimerState.Running) return
        val paused = run ?: return
        stopCountdown()
        _state.value = RestTimerState.Paused(paused.remainingNow(), paused.total)
    }

    /**
     * Continues a paused countdown from exactly the frozen remainder, keeping the
     * original total so a progress indicator does not jump.
     *
     * A no-op unless the timer is paused. A timer paused with nothing left — possible
     * only by pausing in the instant between the countdown expiring and its `Finished`
     * being published — finishes immediately.
     */
    fun resume() {
        val paused = _state.value as? RestTimerState.Paused ?: return
        countDownFrom(remaining = paused.remaining, total = paused.total)
    }

    /**
     * Moves the countdown by [delta] — forward for a positive one, back for a negative —
     * without restarting it.
     *
     * This is the "+15s" and "−15s" on the rest sheet, and it belongs here rather than in the
     * screen for the reason [pause] does: what is being adjusted is the time *actually* left,
     * which only this class knows. A caller reading [state] would be working from the last
     * whole second published and would quietly lose the fraction of a second since it — and
     * would have to rebuild a [RestTimerState.Running] to hand back, which is a state machine
     * reimplemented at a button.
     *
     * ### What happens to the total
     *
     * It is kept, so the progress bar carries on from where it was rather than resetting: a
     * rest extended with twenty seconds left is still a rest "of 2:00", now 35 seconds from
     * its end. The one case where it cannot be kept is an extension past the total itself —
     * [RestTimerState.Running] forbids a remainder longer than its total — and there the total
     * grows to exactly the new remainder, which is the smallest change that keeps the
     * invariant. That is a bar that jumps back to empty, and it is the honest drawing of it:
     * the rest really has been made longer than it ever was.
     *
     * ### Where it applies
     *
     * A running rest and a paused one, each staying in the state it was in — an adjustment is
     * not a resume. Idle and finished are no-ops, matching [pause] and [resume]: the buttons
     * that raise this are drawn inert in both, and a race between the render and the tap is
     * not something to report.
     *
     * Shortening past zero — of a running rest or a paused one — lands on
     * [RestTimerState.Finished] rather than on a rest with nothing left, which is the same
     * answer [restore] gives a deadline that has already gone by. It is deliberately *not*
     * [RestTimerState.Idle]: the user asked for the rest to be over, not to be called off, and
     * a caller that alerts on `Finished` should ring here exactly as it would have fifteen
     * seconds later.
     */
    fun adjust(delta: Duration) {
        when (val current = _state.value) {
            is RestTimerState.Running -> {
                // The live remainder rather than the published one, for the reason [pause]
                // takes it from the same place. Null only if a command raced this one in
                // between, in which case that command's state is the newer answer.
                val live = run ?: return
                moveTo(remaining = live.remainingNow() + delta, total = current.total, running = true)
            }

            is RestTimerState.Paused ->
                moveTo(remaining = current.remaining + delta, total = current.total, running = false)

            RestTimerState.Idle, RestTimerState.Finished -> Unit
        }
    }

    /**
     * Puts the timer at [remaining] out of [total], counting or frozen per [running].
     *
     * The shared tail of [adjust]: the clamping, the total that has to grow rather than be
     * violated, and the shortening that runs off the end are the same three questions either
     * way, and only the landing state differs.
     */
    private fun moveTo(remaining: Duration, total: Duration, running: Boolean) {
        if (remaining <= Duration.ZERO) {
            stopCountdown()
            _state.value = RestTimerState.Finished
            return
        }
        val held = maxOf(total, remaining)
        if (running) {
            countDownFrom(remaining = remaining, total = held)
        } else {
            stopCountdown()
            _state.value = RestTimerState.Paused(remaining, held)
        }
    }

    /**
     * Abandons the countdown and returns to [RestTimerState.Idle].
     *
     * Deliberately not [RestTimerState.Finished]: dismissing a rest is not the same
     * event as resting through it, and a caller that alerts on `Finished` must not be
     * made to alert here.
     */
    fun cancel() {
        stopCountdown()
        _state.value = RestTimerState.Idle
    }

    /**
     * Adopts [state] wholesale, taking over a rest that was already in flight.
     *
     * This is the entry point for surviving process death. Everything this controller
     * holds is in memory, so a rest does not outlive the process on its own; what
     * outlives it is a *deadline* someone else persisted, and `restore` is how the
     * reconstructed state gets back in.
     *
     * ### The contract for the caller
     *
     * Persist an **absolute instant**, `now + remaining`, never the remainder itself —
     * a remainder is stale the moment it is written. Read it back as
     * `remaining = deadline - now`, and note that both readings must come from a clock
     * that keeps counting while the device is asleep. On Android that is
     * `SystemClock.elapsedRealtime()`; it is emphatically *not* [TimeSource.Monotonic],
     * this class's own default, which is `System.nanoTime()` and stops during suspend.
     * The same substitution applies to the [timeSource] constructor parameter.
     *
     * A rest whose deadline has already passed is the caller's decision to express,
     * because [RestTimerState.Running] cannot hold a non-positive remainder — pass
     * [RestTimerState.Finished] for a rest that elapsed while the process was gone.
     * Restoring `Finished` is a *display* fact, not an alert: an alarm that survives
     * process death is what alerts at the deadline, and it fires whether or not this
     * controller ever came back. A caller that rings a bell on `Finished` should be
     * reading it from that alarm, not from a restore.
     *
     * Unlike [resume], which continues from a remainder this controller itself froze,
     * `restore` takes the remainder on faith and can express one that is a fraction of
     * its total. Like [start], it applies in any state and replaces whatever is running.
     */
    fun restore(state: RestTimerState) {
        when (state) {
            is RestTimerState.Running -> countDownFrom(state.remaining, state.total)
            RestTimerState.Idle, RestTimerState.Finished, is RestTimerState.Paused -> {
                stopCountdown()
                _state.value = state
            }
        }
    }

    /** Replaces whatever is running with a countdown of [remaining], out of [total]. */
    private fun countDownFrom(remaining: Duration, total: Duration) {
        stopCountdown()
        val stamp = generation.get()
        if (remaining <= Duration.ZERO) {
            _state.value = RestTimerState.Finished
            return
        }
        val started = Run(mark = timeSource.markNow(), from = remaining, total = total)
        run = started
        _state.value = RestTimerState.Running(remaining, total)
        countdown = scope.launch { countDown(started, stamp) }
    }

    /** Cancels the current countdown, if any, and invalidates anything it has yet to publish. */
    private fun stopCountdown() {
        generation.incrementAndGet()
        countdown?.cancel()
        countdown = null
        run = null
    }

    /**
     * Sleeps to each whole tick of [run] in turn and publishes what is left, then
     * [RestTimerState.Finished] at the deadline.
     *
     * `target` is an offset from the run's mark rather than a running subtraction, so
     * lateness on one wake-up shortens the next sleep instead of pushing the deadline
     * out. It is clamped to the run's length, which is what makes a rest that is not a
     * whole number of ticks (a resumed one, usually) finish on time rather than at the
     * next tick boundary past its deadline.
     */
    private suspend fun countDown(run: Run, stamp: Long) {
        var target = tick.coerceAtMost(run.from)
        while (true) {
            delay(target - run.mark.elapsedNow())
            if (generation.get() != stamp) return
            val remaining = run.from - target
            if (remaining <= Duration.ZERO) {
                _state.value = RestTimerState.Finished
                return
            }
            _state.value = RestTimerState.Running(remaining, run.total)
            target = (target + tick).coerceAtMost(run.from)
        }
    }

    /**
     * One countdown's immutable facts: when it started ([mark]), how much it had left at
     * that moment ([from], which is below [total] for a resumed run) and the total it is
     * a remainder of.
     */
    private class Run(val mark: TimeMark, val from: Duration, val total: Duration) {

        /** What is left right now, floored at zero once the deadline has passed. */
        fun remainingNow(): Duration = (from - mark.elapsedNow()).coerceAtLeast(Duration.ZERO)
    }

    companion object {

        /** How often a running countdown republishes itself. */
        val DEFAULT_TICK: Duration = 1.seconds
    }
}
