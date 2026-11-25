package com.titan.core.domain.repository

import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.WorkoutActivity
import com.titan.core.domain.model.WorkoutSession

/**
 * The local history, as the Health Connect sync needs to see it.
 *
 * A repository of its own rather than three more methods on [SessionRepository], because what it
 * answers is narrow and joined: a finished session together with the disciplines of what was
 * done in it, which is the one thing [WorkoutActivity.of] needs and the one thing the session
 * repository has no single read for. The import's duplicate check asks it for every session in a
 * month, so it is one query per question here rather than a query per session over there.
 */
interface HealthConnectSessionRepository {

    /**
     * The finished session [sessionId] with what it was, or **null** when there is no such
     * session or it is still in progress.
     */
    suspend fun getFinished(sessionId: Long): FinishedSessionActivity?

    /**
     * Every finished session that **started** in `[startedFrom, startedUntil)`, imported ones
     * included, oldest first.
     *
     * By start, because that is what the duplicate check compares: an `ExerciseSessionRecord` and
     * the session it duplicates agree on when the workout began.
     */
    suspend fun getFinishedStartedBetween(
        startedFrom: Long,
        startedUntil: Long,
    ): List<FinishedSessionActivity>

    /**
     * Stores sessions read from Health Connect, and says how many were new.
     *
     * Every one must carry a [WorkoutSession.healthConnectId]. A session whose id is already in
     * the history is skipped rather than refused: `health_connect_id` is unique, so two imports
     * racing each other — a double tap — store each record once between them, and the count each
     * reports is what it actually added.
     *
     * @return how many of [sessions] were inserted.
     */
    suspend fun insertImported(sessions: List<WorkoutSession>): Int
}

/**
 * A finished session and the disciplines of the exercises it completed a set of.
 *
 * @property disciplines one entry per exercise with a completed set, null for an exercise that
 *   has no discipline. Empty for an imported session, which has no exercises.
 */
data class FinishedSessionActivity(
    val session: WorkoutSession,
    val disciplines: List<Discipline?>,
) {

    /**
     * What the session was: what Health Connect said, for one imported from it, and otherwise
     * what its exercises make it.
     */
    val activity: WorkoutActivity
        get() = session.healthConnectActivity ?: WorkoutActivity.of(disciplines)
}
