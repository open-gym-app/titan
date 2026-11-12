package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import kotlinx.coroutines.flow.first

/**
 * The session this state is carrying, or null when no session is open.
 * `Finished` counts as closed: its summary describes a session that is over.
 */
internal val SessionState.openSessionId: Long?
    get() = when (this) {
        is SessionState.Active -> sessionId
        is SessionState.Paused -> sessionId
        SessionState.Idle, is SessionState.Finished -> null
    }

/**
 * Reads one session row, failing with [SessionError.SessionNotFound] instead of
 * returning null, so the lifecycle use cases can unwrap it with `getOrElse`.
 */
internal suspend fun SessionRepository.loadSession(sessionId: Long): Result<WorkoutSession> =
    getById(sessionId).first()
        ?.let { Result.success(it) }
        ?: Result.failure(SessionError.SessionNotFound(sessionId))

/**
 * Fails unless [openSessionId] — the session a lifecycle transition is about to act on — is the
 * one [expected] names.
 *
 * Passes when [expected] is null: a caller that names no session accepts the open one, which is
 * how every in-app control that is drawn over the open session behaves. The row is read only when
 * [expected] carries a creation time to compare, so a caller naming the id alone costs no query.
 */
internal suspend fun SessionRepository.requireExpected(
    expected: SessionRef?,
    openSessionId: Long?,
): Result<Unit> {
    if (expected == null) return Result.success(Unit)

    val matches = openSessionId == expected.sessionId &&
        (expected.createdAt == null || getById(openSessionId).first()?.let(expected::matches) == true)

    return when {
        matches -> Result.success(Unit)
        else -> Result.failure(SessionError.NotTheOpenSession(expected, openSessionId))
    }
}

/**
 * The running session's id, or the refusal for there not being one.
 *
 * The first two lines of every use case that edits what is *inside* a workout, which by now is
 * eight of them. Written once because it is one rule — a session that is not `Active` takes no
 * writes — and because the refusal it produces has to name the state it actually saw.
 */
internal suspend fun SessionStateRepository.activeSessionId(): Result<Long> =
    when (val current = observe().first()) {
        is SessionState.Active -> Result.success(current.sessionId)
        else -> Result.failure(SessionError.SessionNotActive(current))
    }

/**
 * Fails unless [sessionExerciseId] is one of [sessionId]'s own exercises.
 *
 * A foreign key that resolves is not the same as one that belongs here: a screen left behind by
 * a session that has since been finished and replaced names rows that still exist, and writing
 * to them would edit a workout the user is not looking at.
 */
internal suspend fun SessionRepository.requireExerciseOf(
    sessionExerciseId: Long,
    sessionId: Long,
): Result<Unit> =
    when {
        getExercises(sessionId).first().any { it.id == sessionExerciseId } -> Result.success(Unit)
        else -> Result.failure(SessionError.ExerciseNotInSession(sessionExerciseId, sessionId))
    }

/**
 * One exercise's sets, in the order they were performed, with every check between them and the
 * running session.
 *
 * Two questions in the order they have to be asked — is a session running, is this exercise one
 * of its own — and then the read. The order is what makes the refusals mean anything: a set
 * aimed at a workout that is over must be reported as a session that is not active, not as a set
 * that cannot be found.
 *
 * The whole table rather than the one set, because the callers that only want one
 * ([EditSetUseCase], through [loggedSetOf]) want it out of a list the others need anyway: a
 * deletion renumbers what is left and a restore numbers itself against it, so reading the row on
 * its own would be a second query for a table already in hand.
 */
internal suspend fun orderedSetsOf(
    sessions: SessionRepository,
    state: SessionStateRepository,
    sessionExerciseId: Long,
): Result<List<WorkoutSet>> {
    val sessionId = state.activeSessionId().getOrElse { return Result.failure(it) }
    sessions.requireExerciseOf(sessionExerciseId, sessionId)
        .getOrElse { return Result.failure(it) }

    return Result.success(SetNumbering.inOrder(sessions.getSets(sessionExerciseId).first()))
}

/**
 * The set [setId] of [sessionExerciseId], checked all the way up to the running session.
 *
 * [orderedSetsOf] with the last question asked of the result, which is what a correction wants:
 * one row, refused by name if the table it was supposed to be in does not hold it.
 */
internal suspend fun loggedSetOf(
    sessions: SessionRepository,
    state: SessionStateRepository,
    sessionExerciseId: Long,
    setId: Long,
): Result<WorkoutSet> {
    val sets = orderedSetsOf(sessions, state, sessionExerciseId)
        .getOrElse { return Result.failure(it) }

    return sets.firstOrNull { it.id == setId }
        ?.let { Result.success(it) }
        ?: Result.failure(SessionError.SetNotInExercise(setId, sessionExerciseId))
}
