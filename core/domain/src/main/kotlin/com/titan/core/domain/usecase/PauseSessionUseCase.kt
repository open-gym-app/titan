package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Suspends the running session, moving the state machine to [SessionState.Paused].
 *
 * Only [SessionState.Active] can be paused; `Idle`, `Paused` and `Finished` are
 * refused with [SessionError.SessionNotActive].
 *
 * The session row stays `is_in_progress = 1` — pausing is a UI-facing distinction,
 * not a persistence one — but its `last_active_at` is stamped, because that is what
 * resume-after-restart orders on.
 *
 * A caller about a particular session passes it as `expected`, and the pause is refused
 * with [SessionError.NotTheOpenSession] when the running session is another — see
 * [SessionRef].
 *
 * Runs under [SessionLifecycleLock] so it cannot interleave with another transition.
 */
class PauseSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /** @param expected the session the caller means, or null to pause whichever one is running. */
    suspend operator fun invoke(expected: SessionRef? = null): Result<SessionState.Paused> =
        lock.withTransition {
            val current = sessionStateRepository.observe().first()
            val active = current as? SessionState.Active
                ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

            sessionRepository.requireExpected(expected, active.sessionId)
                .getOrElse { return@withTransition Result.failure(it) }

            val session = sessionRepository.loadSession(active.sessionId)
                .getOrElse { return@withTransition Result.failure(it) }

            val now = clock.nowMillis()
            sessionRepository.update(session.copy(lastActiveAt = now, updatedAt = now))

            val paused = SessionState.Paused(sessionId = active.sessionId, pausedAt = now)
            sessionStateRepository.update(paused)
            Result.success(paused)
        }
}
