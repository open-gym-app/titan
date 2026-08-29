package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.FinishedSession
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionProgression
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.stats.SessionStatsCalculator
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Closes out the running session: marks the row `is_in_progress = 0`, computes its
 * [SessionSummary] and moves the state machine to [SessionState.Finished].
 *
 * Both [SessionState.Active] and [SessionState.Paused] can be finished — a user who
 * paused and walked away should still be able to close the session out. `Idle` and
 * `Finished` are refused with [SessionError.SessionNotActive].
 *
 * A session with no completed sets is refused with [SessionError.NoSetsLogged] and
 * left in progress, so the user can either log something or discard it outright —
 * see [DiscardSessionUseCase], which is the other way out of that state.
 *
 * The in-progress draft is cleared on the way out. A draft outliving the session it
 * describes is what makes the app offer to resume a workout that is already over, so
 * this is not optional cleanup — and it belongs here rather than in the screen for the
 * same reason the row's `is_in_progress` flag does: the session's lifetime is the use
 * case's to end, and a second closer that forgot the draft would be a bug nobody sees
 * until the next launch.
 *
 * Runs under [SessionLifecycleLock] so a double-tapped finish cannot close the same
 * session out twice.
 *
 * ### The gamification cascade runs from here
 *
 * Finishing a workout is also what pays for it — records, XP, the streak, achievements — and
 * [SessionCompletionPipeline] is the one ordered place all of that happens. This is where it
 * is invoked from, inside the transition above, and the pipeline's own doc carries the
 * argument for that over a call from the screen: a caller that forgets is a workout that
 * silently pays nothing, and letting the lock go first would let another session land between
 * this row being closed out and the lifetime counters the cascade reads.
 *
 * It runs **last**, after the row, the state and the draft are all settled, and it cannot fail
 * this use case: the pipeline records what it could not do rather than throwing, so a store
 * that refuses an XP write leaves a workout that is saved and a payoff that is short, which is
 * the right half of that trade. What it returns comes back in [FinishedSession.progression],
 * so the recap screen draws the celebration from this call rather than re-querying for it.
 *
 * With no cascade wired that property is **null**, not an empty [SessionProgression]. An empty
 * one is what a cascade that ran and moved nothing returns — a session that broke no records on
 * a day already counted — and a caller that has to draw a recap needs to tell "nothing to
 * celebrate" from "nobody worked out whether there was".
 *
 * @param completeSession the cascade, or **null** to close sessions out without one. Null is
 *   not a test seam: [com.opengym.core.domain.repository.GamificationRepository] has no
 *   implementation until #35 lands, so there is nothing for DI to build a pipeline out of yet
 *   and the app wires this without one — which is exactly the behaviour it has today. The
 *   binding becomes non-optional when there is something to bind.
 */
class FinishSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
    private val completeSession: SessionCompletionPipeline? = null,
) {

    suspend operator fun invoke(): Result<FinishedSession> =
        lock.withTransition {
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
    ): FinishedSession {
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
        // After the state, so a failure to clear leaves a stale draft over a session that
        // is correctly closed out rather than a live session with no draft. The first is
        // an offer to resume that the restore path already has to reject — a draft may
        // legitimately name a session that no longer exists — and the second would be a
        // workout the user could lose their place in.
        //
        // Best-effort for the same reason: by here the row and the state are already
        // committed, so a store that refuses the clear must not turn a finish that
        // happened into a failure the caller reports as "nothing was saved". That answer
        // would be false, and it would strand the user on a screen whose every other exit
        // is refused for a session that is no longer open.
        runCatching { inProgressSessionRepository.clear() }

        // Last, and after everything the session itself owns has committed — see the class
        // doc. A cascade that ran before the state was published would pay for a session the
        // app does not yet consider over, and one that could fail this call would take a saved
        // workout down with a refused XP write.
        val progression = completeSession?.invoke(session.id, Instant.ofEpochMilli(now))
        return FinishedSession(state = finished, progression = progression)
    }
}
