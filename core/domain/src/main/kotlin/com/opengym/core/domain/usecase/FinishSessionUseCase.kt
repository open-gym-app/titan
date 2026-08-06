package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.stats.SessionStatsCalculator
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Closes out the running session: marks the row `is_in_progress = 0`, computes its
 * [SessionSummary] and moves the state machine to [SessionState.Finished].
 *
 * Both [SessionState.Active] and [SessionState.Paused] can be finished — a user who
 * paused and walked away should still be able to close the session out. `Idle` and
 * `Finished` are refused with [SessionError.SessionNotActive].
 *
 * A session with no completed sets is refused with [SessionError.NoSetsLogged] and
 * left in progress, so the user can either log something or discard it outright.
 *
 * Runs under [SessionLifecycleLock] so a double-tapped finish cannot close the same
 * session out twice.
 */
class FinishSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    suspend operator fun invoke(): Result<SessionState.Finished> = lock.withTransition {
        val current = sessionStateRepository.observe().first()
        val sessionId = current.openSessionId
            ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

        val session = sessionRepository.loadSession(sessionId)
            .getOrElse { return@withTransition Result.failure(it) }

        // One read for the whole session rather than one per exercise: the sets
        // carry their own sessionExerciseId, so the exercise count is a grouping
        // rather than a second query.
        val performed = sessionRepository.getSetsForSession(sessionId).first()
            .filter(WorkoutSet::isCompleted)

        if (performed.isEmpty()) {
            Result.failure(SessionError.NoSetsLogged)
        } else {
            Result.success(closeOut(session, performed))
        }
    }

    private suspend fun closeOut(
        session: WorkoutSession,
        performed: List<WorkoutSet>,
    ): SessionState.Finished {
        val now = clock.nowMillis()
        sessionRepository.update(
            session.copy(
                completedAt = now,
                isInProgress = false,
                lastActiveAt = now,
                updatedAt = now,
            ),
        )

        val stats = SessionStatsCalculator.calculate(performed, session.createdAt, now)
        val finished = SessionState.Finished(
            SessionSummary(
                sessionId = session.id,
                startedAt = session.createdAt,
                finishedAt = now,
                // Clamped: a backwards device-clock adjustment mid-session would
                // otherwise surface as a negative duration in the UI.
                durationMillis = (now - session.createdAt).coerceAtLeast(0),
                exerciseCount = stats.exerciseBreakdown.size,
                totalSets = stats.totalSets,
                totalVolume = stats.totalVolume,
            ),
        )
        sessionStateRepository.update(finished)
        return finished
    }
}
