package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Puts a session back into [SessionState.Active], either from [SessionState.Paused]
 * or by recovering one the state machine has lost track of.
 *
 * The restored `startedAt` is read back from the session row rather than recomputed,
 * so neither a pause/resume cycle nor a recovery restarts the elapsed-time clock.
 *
 * ### Recovery
 *
 * The state machine is in-memory, but the session row is not: a process restart
 * leaves the state at `Idle` with `is_in_progress = 1` still on disk. Since
 * [StartSessionUseCase] refuses to open a second session while that row exists,
 * this use case is the way back to it — resuming from `Idle` or `Finished` adopts
 * whatever session `getActive` resolves to. Without that, a restart mid-workout
 * would strand the session: unreachable by every transition, and blocking new ones.
 *
 * Once M3-07 makes the state itself durable this path becomes the rare case rather
 * than the every-restart case, but it stays correct either way — the row is the
 * authority on what is in progress.
 *
 * Refused with [SessionError.SessionNotPaused] when there is nothing to resume:
 * from `Active` (already running), or from `Idle`/`Finished` with no session in
 * progress.
 *
 * Runs under [SessionLifecycleLock] so it cannot interleave with another transition.
 */
class ResumeSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    suspend operator fun invoke(): Result<SessionState.Active> =
        lock.withTransition {
            val current = sessionStateRepository.observe().first()
            val sessionId = resumableSessionId(current)
                ?: return@withTransition Result.failure(SessionError.SessionNotPaused(current))

            val session = sessionRepository.loadSession(sessionId)
                .getOrElse { return@withTransition Result.failure(it) }

            val now = clock.nowMillis()
            sessionRepository.update(session.copy(lastActiveAt = now, updatedAt = now))

            val active = SessionState.Active(sessionId = session.id, startedAt = session.createdAt)
            sessionStateRepository.update(active)
            Result.success(active)
        }

    private suspend fun resumableSessionId(current: SessionState): Long? =
        when (current) {
            // Already running: resuming it would be a no-op that hid a caller's bug.
            is SessionState.Active -> null

            is SessionState.Paused -> current.sessionId

            SessionState.Idle, is SessionState.Finished -> sessionRepository.getActive().first()?.id
        }
}
