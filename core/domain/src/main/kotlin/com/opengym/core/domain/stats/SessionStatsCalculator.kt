package com.opengym.core.domain.stats

import com.opengym.core.domain.model.ExerciseStats
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet

/**
 * Rolls a session's sets up into the figures a summary or live session screen shows.
 *
 * Pure: it takes the sets it is given and reads no repository, so a caller that has
 * already loaded a session (`SessionRepository.getSetsForSession`) pays for one read
 * and can recompute as often as it likes.
 *
 * Only completed sets count, and the filter lives here rather than at the call site —
 * see [SessionStats].
 */
object SessionStatsCalculator {

    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * Aggregates [sets] over the window `[startedAt, endedAt]`, both epoch millis UTC.
     *
     * A window that runs backwards — which a device clock adjusted mid-session can
     * produce — is clamped to zero rather than reported as a negative duration.
     */
    fun calculate(
        sets: List<WorkoutSet>,
        startedAt: Long,
        endedAt: Long,
    ): SessionStats {
        val performed = sets.filter(WorkoutSet::isCompleted)

        // groupBy keeps insertion order, so the breakdown comes out in the order the
        // sets arrived — session order — and needs no re-sorting downstream.
        val breakdown = performed.groupBy(WorkoutSet::sessionExerciseId)
            .map { (sessionExerciseId, exerciseSets) ->
                ExerciseStats(
                    sessionExerciseId = sessionExerciseId,
                    totalSets = exerciseSets.size,
                    totalVolume = VolumeCalculator.totalVolume(exerciseSets),
                )
            }

        return SessionStats(
            totalSets = performed.size,
            totalVolume = breakdown.sumOf(ExerciseStats::totalVolume),
            durationSeconds = durationSeconds(startedAt, endedAt),
            exerciseBreakdown = breakdown,
        )
    }

    /**
     * How long the window `[startedAt, endedAt]` is, in whole seconds.
     *
     * Exposed because a live session's clock moves once a second while its sets do not, and a
     * caller that redrew the clock by re-running [calculate] would roll every set of the workout
     * up again to change one number. It answers that caller with the arithmetic [calculate]
     * itself uses, rather than leaving the same expression written out in two places to drift.
     */
    fun durationSeconds(
        startedAt: Long,
        endedAt: Long,
    ): Long = (endedAt - startedAt).coerceAtLeast(0) / MILLIS_PER_SECOND

    /**
     * Same aggregation for a [session] and its [sets], taking the window from the
     * session row: it began at `createdAt`, and it ends at `completedAt` once it has
     * been closed out or at [now] while it is still running.
     */
    fun calculate(
        session: WorkoutSession,
        sets: List<WorkoutSet>,
        now: Long,
    ): SessionStats = calculate(sets, session.createdAt, session.completedAt ?: now)
}
