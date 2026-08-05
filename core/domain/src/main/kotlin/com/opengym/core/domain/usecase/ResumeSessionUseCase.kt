package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Puts a suspended session back into [SessionState.Active].
 *
 * Only [SessionState.Paused] can be resumed; anything else is refused with
 * [SessionError.SessionNotPaused]. The restored `startedAt` is read back from the
 * session row rather than recomputed, so a pause/resume cycle does not restart the
 * elapsed-time clock.
 */
class ResumeSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    suspend operator fun invoke(): Result<SessionState.Active> {
        val current = sessionStateRepository.observe().first()
        val paused = current as? SessionState.Paused
            ?: return Result.failure(SessionError.SessionNotPaused(current))

        val session = sessionRepository.loadSession(paused.sessionId)
            .getOrElse { return Result.failure(it) }

        val now = clock.nowMillis()
        sessionRepository.update(session.copy(lastActiveAt = now, updatedAt = now))

        val active = SessionState.Active(sessionId = session.id, startedAt = session.createdAt)
        sessionStateRepository.update(active)
        return Result.success(active)
    }
}
