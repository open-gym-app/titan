package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Opens a new workout session and moves the state machine to
 * [SessionState.Active].
 *
 * Refuses with [SessionError.SessionAlreadyActive] when one is already running.
 * `Idle` and `Finished` both start cleanly — `Finished` just means the previous
 * session was closed out and never cleared.
 *
 * The refusal also covers a session that is in progress on disk but absent from the
 * state machine, e.g. after a process restart. [ResumeSessionUseCase] is the way
 * back to that session; a caller handling [SessionError.SessionAlreadyActive] should
 * offer resuming it, not treat it as a dead end.
 *
 * The check and the insert run under [SessionLifecycleLock], so two concurrent
 * starts cannot both pass the check and open a session each.
 */
class StartSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    suspend operator fun invoke(
        templateId: Long? = null,
        notes: String? = null,
    ): Result<SessionState.Active> = lock.withTransition {
        runningSessionId()?.let {
            return@withTransition Result.failure(SessionError.SessionAlreadyActive(it))
        }

        val now = clock.nowMillis()
        val sessionId = sessionRepository.insert(
            WorkoutSession(
                templateId = templateId,
                isInProgress = true,
                lastActiveAt = now,
                notes = notes,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val started = SessionState.Active(sessionId = sessionId, startedAt = now)
        sessionStateRepository.update(started)
        Result.success(started)
    }

    /**
     * The session blocking a new start, if any.
     *
     * Falls back to the persisted `is_in_progress` row when the state machine
     * claims nothing is running: a process restart resets the state to `Idle`
     * while the row survives, and starting a second session there would leave two
     * sessions in progress with only the newer one reachable.
     */
    private suspend fun runningSessionId(): Long? =
        sessionStateRepository.observe().first().openSessionId
            ?: sessionRepository.getActive().first()?.id
}
