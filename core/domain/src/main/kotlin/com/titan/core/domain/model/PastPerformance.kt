package com.titan.core.domain.model

/**
 * One earlier time an exercise was performed: its working sets, in the order they were done.
 *
 * What the next-load engine (#M7-15) reads history as. The same unit the active session's
 * `PREV` column shows — one `session_exercise` slot, its completed non-warm-up sets — so a
 * suggestion and the reference it is drawn beside are two readings of one thing; see
 * [com.titan.core.domain.repository.SessionRepository.getPreviousSets] for what counts as a
 * set and why a slot rather than a session.
 *
 * Not an [ExerciseAttempt], deliberately. That is a session rolled up to four figures for a
 * chart, and a rule that asks "did *every* set reach the top of the range" cannot be answered
 * from a top set and a total. This is the sets themselves.
 *
 * @property sets completed working sets in set order, never empty — a slot with nothing to
 *   judge is not returned rather than returned hollow, per the repository contract.
 */
data class PastPerformance(val sets: List<WorkoutSet>) {
    init {
        require(sets.isNotEmpty()) { "a past performance must hold at least one working set" }
    }
}
