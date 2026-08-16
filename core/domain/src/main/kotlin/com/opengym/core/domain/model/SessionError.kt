package com.opengym.core.domain.model

/**
 * Every way an operation on a session can be refused.
 *
 * The use cases return these inside a [Result] rather than throwing, so a caller
 * can exhaustively `when` over the failure and render it. They extend
 * [IllegalStateException] so the convenience path still behaves the way a Kotlin
 * caller expects: `getOrThrow()` raises an `IllegalStateException` carrying a
 * message that names the transition that was refused.
 */
sealed class SessionError(message: String) : IllegalStateException(message) {

    /** A session is already running, so a second one cannot be started. */
    data class SessionAlreadyActive(val sessionId: Long) : SessionError(
        "cannot start a session while session $sessionId is still in progress",
    )

    /** The operation needs a running session and there is none in [state]. */
    data class SessionNotActive(val state: SessionState) : SessionError(
        "expected an active session, but the current state is ${state.describe()}",
    )

    /** Resuming needs a suspended session and there is none in [state]. */
    data class SessionNotPaused(val state: SessionState) : SessionError(
        "expected a paused session, but the current state is ${state.describe()}",
    )

    /** A session with no completed sets is not worth recording, so finishing it is refused. */
    data object NoSetsLogged : SessionError(
        "cannot finish a session with no completed sets",
    )

    /**
     * The set could not be logged, for a reason that is about the *set* rather than
     * about the session's state.
     *
     * [reason] is `SetValidator`'s own wording where the set failed a bound or a shape
     * rule, so a screen can show it as it stands; the validator names the field, its
     * bound and the value it saw, which is more than a caller could reconstruct from a
     * type. It also covers a set aimed at an exercise that is not in the running
     * session, which is not a bound violation but is refused in the same breath and
     * for the same reason — see [com.opengym.core.domain.usecase.LogSetUseCase].
     *
     * The one refusal here that is not a lifecycle transition, which is why the class
     * is documented as "an operation on a session" rather than as a transition. Putting
     * it elsewhere would mean a screen that logs sets and finishes the session having
     * two failure hierarchies to `when` over for one button each.
     */
    data class SetRejected(val reason: String) : SessionError(
        "the set was refused: $reason",
    )

    /**
     * The exercise named is not part of the session being worked on.
     *
     * A stale screen, in practice: one left behind by a session that has since been finished
     * and replaced. The row it names may well exist and resolve — a foreign key that resolves
     * is not the same as one that belongs here — which is why this is checked rather than left
     * to the database.
     */
    data class ExerciseNotInSession(val sessionExerciseId: Long, val sessionId: Long) : SessionError(
        "exercise $sessionExerciseId is not part of session $sessionId",
    )

    /**
     * The exercise cannot be removed because sets have been logged against it.
     *
     * `session_exercise` cascades to `workout_set`, so removing it would delete work the user
     * actually performed — the one outcome no confirmation dialog makes acceptable. The way
     * past it is to delete the sets first (#99), after which the exercise is empty and goes
     * freely; discarding the whole workout is the other.
     */
    data class ExerciseHasLoggedSets(val sessionExerciseId: Long, val setCount: Int) : SessionError(
        "exercise $sessionExerciseId has $setCount logged set(s) and cannot be removed",
    )

    /**
     * The state machine referenced a session that is not in the repository —
     * a stale state value, e.g. after the underlying row was deleted.
     */
    data class SessionNotFound(val sessionId: Long) : SessionError(
        "session $sessionId does not exist",
    )
}

/** Short, message-safe rendering of a state: names the case without dumping a whole summary. */
private fun SessionState.describe(): String = when (this) {
    SessionState.Idle -> "Idle"
    is SessionState.Active -> "Active(sessionId=$sessionId)"
    is SessionState.Paused -> "Paused(sessionId=$sessionId)"
    is SessionState.Finished -> "Finished(sessionId=${summary.sessionId})"
}
