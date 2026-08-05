package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.repository.SessionRepository
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
