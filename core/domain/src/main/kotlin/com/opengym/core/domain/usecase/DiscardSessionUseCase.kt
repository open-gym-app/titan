package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import kotlinx.coroutines.flow.first

/**
 * Throws the running session away: the row, its exercises, its sets and its draft.
 *
 * The counterpart of [FinishSessionUseCase], and the reason that one can afford to be
 * strict. Finishing is refused for a session with no completed sets, and the back gesture
 * out of a workout is deliberately swallowed so a session cannot be abandoned by
 * accident — between them, a session started by mistake would have no exit at all. This
 * is that exit.
 *
 * Both [SessionState.Active] and [SessionState.Paused] can be discarded, matching finish:
 * a user who paused and thought better of the whole workout is exactly who wants this.
 * `Idle` and `Finished` are refused with [SessionError.SessionNotActive].
 *
 * ### What "discard" deletes
 *
 * Everything. `workout_session` cascades to `session_exercise` and on to `workout_set`,
 * so one delete takes the whole workout, and that is the intent — a discarded session is
 * one the user is saying did not happen, not one to keep an empty record of. It is
 * therefore not the same action as finishing a short session, and the UI must not offer
 * it as a neighbour of finish without saying what it costs.
 *
 * Runs under [SessionLifecycleLock] so it cannot interleave with another transition —
 * notably a finish, which would otherwise be closing out a row this call is deleting.
 */
class DiscardSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * @return the id of the session that was discarded, so a caller can say which one it
     *   was in the message it shows.
     *
     * The row goes first, then the state, then the draft, and the order is deliberate: the
     * row is the only one of the three that a failure would leave the user stuck behind,
     * since [StartSessionUseCase] refuses a second session while it exists. A failure part
     * way through therefore leaves the session still running and reports it, rather than
     * clearing the state over a row that is still there.
     */
    suspend operator fun invoke(): Result<Long> = lock.withTransition {
        val current = sessionStateRepository.observe().first()
        val sessionId = current.openSessionId
            ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

        runCatching {
            sessionRepository.delete(sessionId)
            sessionStateRepository.update(SessionState.Idle)
            // Last, and separately: the draft is a snapshot of a screen, so one left behind
            // costs an offer to resume a workout that no longer exists rather than any
            // data. Clearing it before the delete would be the trade the other way round.
            inProgressSessionRepository.clear()
            sessionId
        }
    }
}
