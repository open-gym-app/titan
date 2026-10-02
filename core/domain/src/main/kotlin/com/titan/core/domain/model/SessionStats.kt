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
 * @property exerciseBreakdown one entry per exercise that contributed a completed
 *   set, in the order the sets were read — which is session order, so the UI can
 *   render it without re-sorting.
 */
data class SessionStats(
    val totalSets: Int,
    val totalVolume: Long,
    val durationSeconds: Long,
    val exerciseBreakdown: List<ExerciseStats>,
)

/**
 * One exercise's contribution to a [SessionStats].
 *
 * Keyed by [sessionExerciseId] rather than by exercise id because that is what a
 * [WorkoutSet] carries, and because the same exercise can legitimately appear
 * twice in one session as two separate `session_exercise` rows.
 */
data class ExerciseStats(
    val sessionExerciseId: Long,
    val totalSets: Int,
    val totalVolume: Long,
)
