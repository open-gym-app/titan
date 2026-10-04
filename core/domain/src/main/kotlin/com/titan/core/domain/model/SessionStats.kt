package com.titan.core.domain.model

/**
 * Aggregates over the sets of one session, computed by
 * [com.titan.core.domain.stats.SessionStatsCalculator].
 *
 * Distinct from [SessionSummary], which is the record written once when a session
 * is closed out. These stats can be recomputed at any point, including mid-session,
 * and carry the per-exercise breakdown a live session screen needs.
 *
 * Only sets flagged `isCompleted` contribute, for the same reason the analytics
 * queries filter on `is_completed = 1`: a session row may hold placeholder sets for
 * planned-but-unperformed work (see PERSISTENCE.md).
 *
 * @property totalSets completed sets across every exercise.
 * @property totalVolume sum of `weight x reps`, in milligram-reps, since weights
 *   are stored in milligrams.
 * @property durationSeconds wall-clock time the session spans, truncated to whole
 *   seconds. Paused stretches are included, matching [SessionSummary.durationMillis].
 * @property timedSeconds the sum of the completed sets' own `duration`, in seconds — how
 *   long the lifter spent holding, running or riding, as opposed to [durationSeconds],
 *   which is how long the session was open. Zero for a session of weights and reps.
 *
 *   A separate figure from [totalVolume] rather than folded into it, because the two are
 *   not the same quantity: a plank adds nothing to tonnage (it moved nothing), and a run is
 *   not a heavy bench session. A recap that showed only volume would read a cardio session
 *   as a session where nothing happened — which is the zero-volume noise this exists to
 *   replace.
 * @property totalDistance the sum of the completed sets' `distance`, in centimetres. Zero
 *   for a session that covered no ground.
 * @property exerciseBreakdown one entry per exercise that contributed a completed
 *   set, in the order the sets were read — which is session order, so the UI can
 *   render it without re-sorting.
 */
data class SessionStats(
    val totalSets: Int,
    val totalVolume: Long,
    val durationSeconds: Long,
    val exerciseBreakdown: List<ExerciseStats>,
    val timedSeconds: Long = 0,
    val totalDistance: Long = 0,
)

/**
 * One exercise's contribution to a [SessionStats].
 *
 * Keyed by [sessionExerciseId] rather than by exercise id because that is what a
 * [WorkoutSet] carries, and because the same exercise can legitimately appear
 * twice in one session as two separate `session_exercise` rows.
 *
 * [timedSeconds] and [totalDistance] are this exercise's share of the session's own — see
 * [SessionStats] — so a breakdown row for a run can quote the run rather than its zero
 * tonnage.
 */
data class ExerciseStats(
    val sessionExerciseId: Long,
    val totalSets: Int,
    val totalVolume: Long,
    val timedSeconds: Long = 0,
    val totalDistance: Long = 0,
)
