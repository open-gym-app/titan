package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
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
 */
class FinishSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    suspend operator fun invoke(): Result<SessionState.Finished> {
        val current = sessionStateRepository.observe().first()
        val sessionId = current.openSessionId
            ?: return Result.failure(SessionError.SessionNotActive(current))

        val session = sessionRepository.loadSession(sessionId)
            .getOrElse { return Result.failure(it) }

        val performed = performedSets(sessionId)
        return if (performed.isEmpty()) {
            Result.failure(SessionError.NoSetsLogged)
        } else {
            Result.success(closeOut(session, performed))
        }
    }

    /**
     * The session's completed sets, grouped by exercise and with the exercises that
     * contributed nothing dropped. Grouped rather than flattened because the summary
     * counts exercises actually performed, not sets alone.
     */
    private suspend fun performedSets(sessionId: Long): List<List<WorkoutSet>> =
        sessionRepository.getExercises(sessionId).first()
            .map { exercise -> sessionRepository.getSets(exercise.id).first().filter(WorkoutSet::isCompleted) }
            .filter { it.isNotEmpty() }

    private suspend fun closeOut(
        session: WorkoutSession,
        performed: List<List<WorkoutSet>>,
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

        val finished = SessionState.Finished(
            SessionSummary(
                sessionId = session.id,
                startedAt = session.createdAt,
                finishedAt = now,
                // Clamped: a backwards device-clock adjustment mid-session would
                // otherwise surface as a negative duration in the UI.
                durationMillis = (now - session.createdAt).coerceAtLeast(0),
                exerciseCount = performed.size,
                totalSets = performed.sumOf { it.size },
                totalVolume = performed.flatten().sumOf { it.volume() },
            ),
        )
        sessionStateRepository.update(finished)
        return finished
    }
}

/**
 * `weight x reps` in milligram-reps. Missing weight or reps (cardio, timed holds)
 * count as zero, mirroring the `COALESCE` in `WorkoutSetDao.getVolumeByWeek`.
 */
private fun WorkoutSet.volume(): Long = (weight ?: 0L) * (reps ?: 0).toLong()
