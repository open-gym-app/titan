package com.opengym.core.domain.timer

import kotlin.time.Duration

/**
 * What the rest timer between two sets is doing right now.
 *
 * Durations are [kotlin.time.Duration] rather than the epoch-millisecond longs the
 * session state uses: a rest timer measures an *interval*, never an instant, and
 * nothing about it is ever written to a row, so there is no schema convention to
 * match here.
 *
 * The legal transitions are:
 *
 * ```text
 * Idle ──start──▶ Running ──pause──▶ Paused ──resume──▶ Running ──▶ Finished
 *                    │                  │                              │
 *                    └──────────────────┴───────cancel─────────────────┴──▶ Idle
 *
 * any state ──start──▶ Running          (restarting is always allowed)
 * any state ──restore──▶ any state      (adopting a rest that outlived the process)
 * ```
 *
 * Within a process `Finished` is reached only by the countdown running out;
 * [RestTimerController.cancel] always lands on `Idle` instead, so a caller that rings a
 * bell on `Finished` does not ring it when the user dismissed the timer. Across a
 * restart the distinction is the caller's to preserve, which is what
 * [RestTimerController.restore] documents.
 */
sealed interface RestTimerState {

    /** No rest is being timed. The state the timer boots into, and where `cancel()` lands. */
    data object Idle : RestTimerState

    /**
     * A rest is counting down. [remaining] is what is left, [total] the duration the
     * current countdown was started with, so a progress indicator has its denominator
     * without having to remember what was passed to `start`.
     *
     * After a pause and resume, [total] stays the *original* total rather than the
     * paused remainder, so the progress ring does not jump when the timer is resumed.
     */
    data class Running(
        val remaining: Duration,
        val total: Duration,
    ) : RestTimerState {
        init {
            require(remaining > Duration.ZERO) { "a running timer has time left, was $remaining" }
            require(total >= remaining) { "remaining ($remaining) cannot exceed the total ($total)" }
        }
    }

    /**
     * The countdown is frozen at [remaining] and consumes no time until resumed.
     *
     * [remaining] is sampled at the moment of the pause, not at the last whole-second
     * emission, so it is the true remainder — pausing one and a half seconds into a
     * 90-second rest freezes 88.5s, not 89s. [RestTimerController.resume] continues
     * from exactly this value.
     *
     * [total] is carried for the same reason [Running] carries it: a paused progress
     * indicator still needs its denominator.
     */
    data class Paused(
        val remaining: Duration,
        val total: Duration,
    ) : RestTimerState {
        init {
            require(remaining >= Duration.ZERO) { "a paused timer cannot owe time, was $remaining" }
            require(total >= remaining) { "remaining ($remaining) cannot exceed the total ($total)" }
        }
    }

    /** The countdown reached zero. Rest is over; this is the state to alert on. */
    data object Finished : RestTimerState
}
