package com.opengym.core.domain.repository

import com.opengym.core.domain.model.SessionState
import kotlinx.coroutines.flow.Flow

/**
 * Holds the lifecycle state of the session currently in progress, separately from
 * [SessionRepository], which owns the persisted rows.
 *
 * The split exists because the two have different lifetimes: the session rows
 * outlive the app process, while "the user has this one paused right now" is a
 * single-valued, latest-wins fact. Keeping it behind its own interface lets the
 * data layer decide where that fact lives — see M3-07 for the persistence
 * contract — without the use cases knowing.
 *
 * Implementations must be conflated and latest-wins: [observe] emits the current
 * state to every new collector immediately, so the use cases can read it with
 * `observe().first()` without a write having to happen first.
 */
interface SessionStateRepository {

    /** The current state, re-emitted on every change. Never empty. */
    fun observe(): Flow<SessionState>

    /** Replaces the current state. */
    suspend fun update(state: SessionState)
}
