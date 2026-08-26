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
sealed class SessionError(
    message: String,
    failure: Throwable? = null,
) : IllegalStateException(message, failure) {

    /** A session is already running, so a second one cannot be started. */
    data class SessionAlreadyActive(val sessionId: Long) :
        SessionError(
            "cannot start a session while session $sessionId is still in progress",
        )

    /** The operation needs a running session and there is none in [state]. */
    data class SessionNotActive(val state: SessionState) :
        SessionError(
            "expected an active session, but the current state is ${state.describe()}",
        )

    /** Resuming needs a suspended session and there is none in [state]. */
    data class SessionNotPaused(val state: SessionState) :
        SessionError(
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
    data class SetRejected(val reason: String) :
        SessionError(
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
    data class ExerciseNotInSession(
        val sessionExerciseId: Long,
        val sessionId: Long,
    ) : SessionError(
        "exercise $sessionExerciseId is not part of session $sessionId",
    )

    /**
     * The set named is not one of that exercise's own.
     *
     * The same staleness [ExerciseNotInSession] describes, one level down: a screen holding a
     * table that has been renumbered under it — by a delete performed elsewhere, or by an undo
     * that has not reached it yet — names a row that may still exist under another exercise.
     * Editing it there would correct a set in a workout nobody is looking at.
     */
    data class SetNotInExercise(
        val setId: Long,
        val sessionExerciseId: Long,
    ) : SessionError(
        "set $setId is not logged against exercise $sessionExerciseId",
    )

    /**
     * The exercise cannot be removed because sets have been logged against it.
     *
     * `session_exercise` cascades to `workout_set`, so removing it would delete work the user
     * actually performed — the one outcome no confirmation dialog makes acceptable. The way
     * past it is to delete the sets first — see
     * [com.opengym.core.domain.usecase.DeleteSetUseCase] — after which the exercise is empty and
     * goes freely; discarding the whole workout is the other.
     */
    data class ExerciseHasLoggedSets(
        val sessionExerciseId: Long,
        val setCount: Int,
    ) : SessionError(
        "exercise $sessionExerciseId has $setCount logged set(s) and cannot be removed",
    )

    /**
     * The order asked for does not name this session's exercises, each exactly once.
     *
     * A screen working from a layout that has changed under it — an exercise added or cut
     * somewhere else since it last read the session. Applying it to whichever rows happened to
     * match would leave the ones it did not name wherever they were, which is not the order
     * anybody asked for, so the workout is left exactly as it was.
     *
     * Note what is *not* here: an order that separates two members of a superset. That is what
     * dragging one of them out of it means, and it is repaired rather than refused — see
     * [com.opengym.core.domain.superset.Supersets.tear].
     */
    data class InvalidExerciseOrder(val reason: String) :
        SessionError(
            "the exercises cannot be reordered like that: $reason",
        )

    /**
     * The set was written, but the renumbering that should have followed it was not.
     *
     * The one refusal here that is partly a success, and it is told apart from the rest for
     * exactly that reason. Deleting a set and restoring one are each two writes — the row, then
     * the numbers of the rows around it — and the second one failing leaves the first one done:
     * a delete whose renumbering failed has taken the set out of the workout, and a restore
     * whose renumbering failed has put it back, at the end of its exercise rather than where it
     * was. A caller that could not tell this from "nothing was written" would drop the undo over
     * a set that has genuinely gone, or tell the user to log again a set that is already there.
     *
     * What is left behind is a gap or a duplicate in one exercise's `set_number`s, which the
     * next delete or restore on that table closes — see
     * [com.opengym.core.domain.usecase.SetNumbering]. Logging closes nothing, but numbers from
     * the highest set rather than the count, so it does not collide with one either.
     *
     * @param set the row as it now stands in the store: the set that was deleted, carrying the
     *   number it had, or the set that was restored, carrying the number it was written with.
     * @param failure what the store raised, kept as the cause so it is not lost behind this.
     */
    data class SetTableNotRenumbered(
        val set: WorkoutSet,
        val failure: Throwable,
    ) : SessionError(
        "set ${set.id} was written but exercise ${set.sessionExerciseId} could not be renumbered",
        failure,
    )

    /**
     * The state machine referenced a session that is not in the repository —
     * a stale state value, e.g. after the underlying row was deleted.
     */
    data class SessionNotFound(val sessionId: Long) :
        SessionError(
            "session $sessionId does not exist",
        )
}

/** Short, message-safe rendering of a state: names the case without dumping a whole summary. */
private fun SessionState.describe(): String =
    when (this) {
        SessionState.Idle -> "Idle"
        is SessionState.Active -> "Active(sessionId=$sessionId)"
        is SessionState.Paused -> "Paused(sessionId=$sessionId)"
        is SessionState.Finished -> "Finished(sessionId=${summary.sessionId})"
    }
