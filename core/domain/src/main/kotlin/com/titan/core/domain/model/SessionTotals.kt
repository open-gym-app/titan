package com.titan.core.domain.model

/**
 * What one session added up to, as a *list* needs it: a handful of figures and nothing else.
 *
 * The third of the three shapes a session's arithmetic comes in, and the only one that can
 * be read for every session at once:
 *
 * - [SessionStats] is computed from a session's sets, in memory, and carries the
 *   per-exercise breakdown a recap or a live session draws. It costs one read per session.
 * - [SessionSummary] is the snapshot [com.titan.core.domain.usecase.FinishSessionUseCase]
 *   writes when a session closes, so it exists only for sessions finished since that use
 *   case shipped.
 * - **This** is an aggregate over the whole `workout_set` table, grouped by session, so a
 *   history list of four hundred sessions is one query rather than four hundred.
 *
 * The figures agree with [SessionStats] by construction — same filter (completed sets only),
 * same arithmetic (`weight x reps`, a missing weight or rep count contributing zero) — which
 * is what stops a row in the list disagreeing with the recap it opens.
 *
 * There is no duration here, deliberately. A session's length is a function of its own row
 * (`createdAt` to `completedAt`) and needs no set at all, so putting it in an aggregate over
 * the sets would be arithmetic done twice in two places.
 *
 * The set durations are a different matter from the session's length, and are here: they
 * are what a cardio session *did* — see [SessionStats.timedSeconds] — and a row for a run
 * that quoted only its zero volume would be a row saying nothing happened.
 *
 * @property totalSets completed sets across every exercise of the session.
 * @property totalVolume sum of `weight x reps` over those sets, in milligram-reps.
 * @property timedSeconds sum of those sets' `duration`, in seconds. Zero for a session of
 *   weights and reps.
 * @property totalDistance sum of those sets' `distance`, in centimetres.
 */
data class SessionTotals(
    val totalSets: Int,
    val totalVolume: Long,
    val timedSeconds: Long = 0,
    val totalDistance: Long = 0,
)
