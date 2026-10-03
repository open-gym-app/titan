package com.titan.core.domain.timer

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The hold stopwatch, in the form that can be written down: which exercise it is timing, and
 * when it started.
 *
 * The count-up counterpart of [RestTimerSnapshot], and persisted for the same reason and in the
 * same way. A stopwatch that is running cannot be written down as "23 seconds so far" — that is
 * stale the moment it is written, and a draft read back after a process death would restore a
 * plank that had been held for 23 seconds however long the process was gone. What survives is
 * the **instant it started**; the elapsed time is derived at load time as `now - startedAt`.
 *
 * Epoch milliseconds UTC, the convention every domain timestamp uses, for the reason
 * [RestTimerSnapshot] gives at length: the only Android elapsed clock that counts device sleep
 * resets on reboot, and a wall clock stepped by a few seconds misjudges a hold by a few seconds,
 * which a lifter corrects by typing over the figure. The *live* reading is not taken from this —
 * the screen times the hold on an elapsed-realtime source, so a clock correction mid-hold does
 * not make the figure jump — and this is only what a restore is seeded from.
 *
 * There is no stopped or paused state. A stopwatch that is stopped has written what it read into
 * the time field of the set it was timing, and from then on that field is the record — carried
 * by the draft as the half-typed set it is — so a stopped stopwatch is simply no snapshot at all.
 *
 * @property sessionExerciseId the `session_exercise` row whose next set is being timed. One per
 *   workout: a lifter holds one plank at a time, so the screen offers the control on every timed
 *   exercise and starts it on one.
 * @property startedAt when the hold began, epoch millis UTC.
 */
@Serializable
data class StopwatchSnapshot(
    val sessionExerciseId: Long,
    val startedAt: Long,
) {

    /**
     * How long the hold has lasted as of [nowMillis], epoch millis UTC.
     *
     * Floored at zero, which only comes up if the wall clock stepped backwards between the save
     * and the load. A hold that reads as having just started is a better answer than a negative
     * one.
     */
    fun elapsedAt(nowMillis: Long): Duration = (nowMillis - startedAt).milliseconds.coerceAtLeast(Duration.ZERO)
}
