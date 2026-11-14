package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionRef
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
 * The state is in memory by design (see `SessionStateRepositoryImpl`), so this is the
 * every-restart case rather than a rare one, and the row is the authority on what is
 * in progress.
 *
 * Refused with [SessionError.SessionNotPaused] when there is nothing to resume:
 * from `Active` (already running), or from `Idle`/`Finished` with no session in
 * progress.
 *
 * ### Naming the session to resume
 *
 * Adopting "whatever `getActive` resolves to" is right for a caller that wants the
 * workout back whichever it is, and wrong for one that is about a particular session:
 * the workout screen for session 7, or the idle nudge's button for it. Those pass the
 * session they mean as `expected`, and the resume is refused with
 * [SessionError.NotTheOpenSession] when the paused or adoptable session is a different
 * one — or the same id with another creation time, for a caller outside the process.
 * See [SessionRef].
 *
 * Runs under [SessionLifecycleLock] so it cannot interleave with another transition.
 */
class ResumeSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * @param expected the session the caller means, or null to resume whichever one is paused or
     *   left in progress. Checked under the lock, against the session this would resume, so a
     *   transition landing in between cannot slip a different workout under it.
     */
    suspend operator fun invoke(expected: SessionRef? = null): Result<SessionState.Active> =
        lock.withTransition {
            val current = sessionStateRepository.observe().first()
            val sessionId = resumableSessionId(current)
                ?: return@withTransition Result.failure(SessionError.SessionNotPaused(current))

            sessionRepository.requireExpected(expected, sessionId)
                .getOrElse { return@withTransition Result.failure(it) }

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
