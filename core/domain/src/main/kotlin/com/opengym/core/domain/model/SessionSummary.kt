package com.opengym.core.domain.model

/**
 * What a finished session amounted to, computed once when the session is closed
 * out so the post-workout screen does not have to re-read every set.
 *
 * Only sets flagged `isCompleted` contribute. A session row may hold placeholder
 * sets (`completed_at = 0`, `is_completed = 0`) for planned-but-unperformed work,
 * and counting those would inflate every figure here — the same reason the
 * analytics queries filter on `is_completed = 1` (see PERSISTENCE.md).
 *
 * @property startedAt session creation time, epoch millis UTC.
 * @property finishedAt when the session was closed out, epoch millis UTC.
 * @property durationMillis wall-clock time from [startedAt] to [finishedAt].
 *   Paused stretches are *included*: the app tracks when a session was suspended,
 *   not how long it accumulated in the active state.
 * @property exerciseCount exercises that contributed at least one completed set,
 *   not every exercise the session was planned with.
 * @property totalVolume sum of `weight x reps` over the completed sets, in
 *   milligram-reps, since weights are stored in milligrams. Sets with no weight
 *   or no reps (cardio, timed holds) contribute zero rather than being dropped,
 *   matching `WorkoutSetDao.getVolumeByWeek`.
 */
data class SessionSummary(
    val sessionId: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMillis: Long,
    val exerciseCount: Int,
    val totalSets: Int,
    val totalVolume: Long,
)
