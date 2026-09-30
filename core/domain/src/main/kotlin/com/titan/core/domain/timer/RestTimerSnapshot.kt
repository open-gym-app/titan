package com.titan.core.domain.timer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A rest timer in the form that can be written down — the persistable twin of
 * [RestTimerState].
 *
 * The two are deliberately different types, because a [RestTimerState] cannot be
 * serialized literally. `Running(remaining = 45s, total = 90s)` is stale the instant
 * it is written: reload that draft ten minutes later and it restores a 45-second rest
 * that should have ended nine minutes ago. What survives a process kill is an
 * **absolute deadline**; the remainder is derived at load time as `deadline - now`.
 *
 * [Paused] is the exception that proves the rule. A paused countdown consumes no time,
 * so its remainder is not stale after any amount of time and is persisted as-is.
 *
 * The deadline is epoch milliseconds UTC — the same convention every `*_at` column and
 * every domain timestamp uses (see PERSISTENCE.md), and the reading
 * [com.titan.core.domain.time.Clock] gives.
 * That is a *wall* clock, chosen over an elapsed-time reading because
 * `SystemClock.elapsedRealtime()`, the only Android elapsed clock that counts device
 * sleep, resets to zero on reboot and makes a persisted deadline meaningless afterwards.
 * For a rest measured in minutes, treating a reboot as "the rest is over" is the better
 * trade — a clock stepped by an NTP sync or a timezone change can misjudge a rest by
 * the size of the step, which for a 90-second countdown means it reads as finished.
 *
 * Converting back is [toStateAt], and it is *this* type's job on purpose: it keeps the
 * notion of an absolute instant out of [RestTimerState] and [RestTimerController],
 * which deal only in intervals.
 *
 * @see toStateAt for the deadline-to-state conversion and what an expired one becomes.
 * @see of for the state-to-deadline conversion.
 */
@Serializable
sealed interface RestTimerSnapshot {

    /** No rest was being timed. */
    @Serializable
    @SerialName("idle")
    data object Idle : RestTimerSnapshot

    /**
     * A rest was counting down towards [deadline], epoch millis UTC, out of [total].
     *
     * Nothing here says the deadline is in the future: whether it has passed is a
     * question about the clock at *load* time, which is what [toStateAt] answers.
     */
    @Serializable
    @SerialName("running")
    data class Running(
        val deadline: Long,
        val total: Duration,
    ) : RestTimerSnapshot {
        init {
            require(total > Duration.ZERO) { "a rest has a positive total, was $total" }
        }
    }

    /**
     * A rest was frozen with [remaining] left, out of [total].
     *
     * Persisted as a remainder rather than a deadline, and correctly so: a paused
     * countdown owes the same time back however long the process was gone.
     */
    @Serializable
    @SerialName("paused")
    data class Paused(
        val remaining: Duration,
        val total: Duration,
    ) : RestTimerSnapshot {
        init {
            require(remaining >= Duration.ZERO) { "a paused timer cannot owe time, was $remaining" }
            require(total >= remaining) { "remaining ($remaining) cannot exceed the total ($total)" }
        }
    }

    /** The rest had already run out when the draft was written. */
    @Serializable
    @SerialName("finished")
    data object Finished : RestTimerSnapshot

    /**
     * The state to hand [RestTimerController.restore] when the draft is read back at
     * [nowMillis], epoch millis UTC.
     *
     * A [Running] deadline that has passed becomes [RestTimerState.Finished] rather
     * than a `Running` with nothing left — the latter does not exist, since
     * [RestTimerState.Running] rejects a non-positive remainder by construction. That
     * is the whole point of persisting a deadline: a draft reloaded well after it was
     * written restores a rest that is *over*, not one with time left.
     *
     * Restoring `Finished` is a display fact, not an alert. Nothing reads a database
     * while the process is dead, so a rest that should have rung while the app was gone
     * did not ring here; that needs an OS-scheduled wake-up, which is out of this
     * module's reach. See [RestTimerController.restore].
     *
     * A remainder longer than the total — possible only if the wall clock stepped
     * backwards between the save and the load — is clamped to the total rather than
     * refused, since a draft is not worth losing over a clock correction.
     */
    fun toStateAt(nowMillis: Long): RestTimerState =
        when (this) {
            Idle -> RestTimerState.Idle

            Finished -> RestTimerState.Finished

            is Paused -> RestTimerState.Paused(remaining, total)

            is Running -> {
                val remaining = (deadline - nowMillis).milliseconds
                if (remaining <= Duration.ZERO) {
                    RestTimerState.Finished
                } else {
                    RestTimerState.Running(remaining.coerceAtMost(total), total)
                }
            }
        }

    companion object {

        /**
         * The persistable form of [state], with its remainder resolved against
         * [nowMillis] (epoch millis UTC) into an absolute deadline.
         *
         * Sub-millisecond precision is dropped, since the deadline is a millisecond
         * count. A rest is measured in minutes, so the rounding is not observable —
         * but it does mean a round trip through this type is not the identity on a
         * `Running` whose remainder is a fraction of a millisecond.
         */
        fun of(
            state: RestTimerState,
            nowMillis: Long,
        ): RestTimerSnapshot =
            when (state) {
                RestTimerState.Idle -> Idle

                RestTimerState.Finished -> Finished

                is RestTimerState.Paused -> Paused(state.remaining, state.total)

                is RestTimerState.Running -> Running(
                    deadline = nowMillis + state.remaining.inWholeMilliseconds,
                    total = state.total,
                )
            }
    }
}
