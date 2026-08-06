package com.opengym.core.domain.model

/**
 * Every way a session lifecycle transition can be refused.
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
