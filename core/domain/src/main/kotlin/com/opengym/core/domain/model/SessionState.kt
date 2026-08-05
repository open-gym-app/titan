package com.opengym.core.domain.model

/**
 * Lifecycle state of the one workout session the user can have running at a time.
 *
 * Timestamps are epoch milliseconds UTC, the same convention every `*_at` column
 * uses (see PERSISTENCE.md), so a state value can be written straight to the
 * session row without conversion.
 *
 * The legal transitions are:
 *
 * ```text
 * Idle ──start──▶ Active ──pause──▶ Paused ──resume──▶ Active
 *                    │                 │
 *                    └─────finish──────┴──▶ Finished ──start──▶ Active
 *
 * Idle / Finished ──resume──▶ Active     (only when a session row is still
 *                                        in progress; see ResumeSessionUseCase)
 * ```
 *
 * That last edge exists because this state is in-memory while the session row is
 * not: after a process restart the state reads `Idle` with a session still open on
 * disk, and resume is what reattaches to it.
 *
 * Anything else is rejected with a [SessionError]; see the individual use cases.
 */
sealed interface SessionState {

    /** Nothing is running. The state the app boots into before any session is resumed. */
    data object Idle : SessionState

    /** A session is running and accepting logged sets. [startedAt] is when it was created. */
    data class Active(val sessionId: Long, val startedAt: Long) : SessionState

    /**
     * A session exists but is suspended. [pausedAt] is when it was suspended; the
     * session's own `createdAt` remains the authority on when it began, so a
     * paused session does not need to carry its start time around.
     */
    data class Paused(val sessionId: Long, val pausedAt: Long) : SessionState

    /** The session has been closed out and its row marked `is_in_progress = 0`. */
    data class Finished(val summary: SessionSummary) : SessionState
}
