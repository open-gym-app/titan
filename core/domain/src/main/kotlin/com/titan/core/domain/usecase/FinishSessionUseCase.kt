package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnectSyncScheduler
import com.titan.core.domain.model.FinishOrigin
import com.titan.core.domain.model.FinishedSession
import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionProgression
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.SessionSummary
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.notification.ProgressionAnnouncer
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.stats.SessionStatsCalculator
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.CancellationException
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
 * A caller about a particular session — the workout screen, the idle nudge's button —
 * passes it as `expected`, and the finish is refused with [SessionError.NotTheOpenSession]
 * when the open session is another. Without it, a page or a notification that outlived its
 * workout would close out whichever one is running now. See [SessionRef].
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
 * ### What the cascade earned is announced from here too
 *
 * A workout can be closed out with nothing of the app on screen — the idle nudge's "Finish
 * now", with the process started from cold to serve it (#178) — and that is exactly the finish
 * whose payoff nobody sees. [announce] is handed the same [SessionProgression] the recap would
 * have drawn, along with the [FinishOrigin] the caller passed; whether it says anything is its
 * own decision, and it never says anything a recap is about to say instead. See
 * [ProgressionAnnouncer].
 *
 * It runs **outside** the lock, unlike the cascade. The cascade has to hold it — another
 * session landing between this row closing out and the lifetime counters being read would pay
 * the wrong figures — while an announcement reads nothing and writes nothing the lock protects.
 * Holding a lifecycle mutex across a call into the notification system would serialise every
 * later transition behind an IPC for no gain.
 *
 * ### The calories are estimated here
 *
 * A session the user has not given a calorie figure is closed out with
 * [EstimateSessionCaloriesUseCase]'s, written in the same update that closes the row, so the
 * recap opens on a figure the user can correct rather than on an empty field. A figure already
 * on the row is the user's and is never replaced. The estimate is best-effort like everything
 * after the row: a failure to read what it needs leaves the calories empty, never the workout
 * unsaved.
 *
 * ### And the workout is handed to Health Connect from here
 *
 * Last of all, [healthConnectSync] is asked to write the session as an `ExerciseSessionRecord`,
 * which it does when the user has granted the write (#54). From here for the reason the cascade
 * is: the idle nudge's finish has no screen to do it from, and a sync that only the workout
 * screen remembered would be a Health Connect history with holes where those workouts were.
 *
 * Asked, not done. The write is an IPC into another app's provider that most users never
 * connected — on Android 14 and later it would still cost every finish a call to read the grant,
 * and below that it can mean starting Health Connect's process from cold — and the recap would
 * wait for it. So the request is queued and the finish returns; see [HealthConnectSyncScheduler]
 * for why the queue is WorkManager and not a coroutine. Best-effort for the reason the
 * announcement is: a queue that refuses the request must not turn a saved workout into a finish
 * that failed.
 *
 * @param completeSession the cascade, or **null** to close sessions out without one. The app
 *   always passes one — `SessionUseCaseModule` binds it non-optionally, since a workout that
 *   silently pays nothing is the failure this would otherwise hide. Null is for the tests of the
 *   lifecycle itself, which are about the session row and not about what finishing it earns.
 * @param announce what says the payoff out loud, or **null** to close sessions out silently.
 *   Null for the same reason [completeSession] is: a caller with no cascade has no progression
 *   to announce, and one that does not care to announce should not have to supply a no-op.
 * @param estimateCalories what prices the workout in kilocalories, or **null** to leave the
 *   calories as they are. The app always passes one; null is for the tests of the lifecycle.
 * @param healthConnectSync what queues the finished session's write to Health Connect, or
 *   **null** to queue nothing. The app always passes one; whether anything is written is the
 *   permission's business, decided when the queued work runs.
 */
class FinishSessionUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
    private val completeSession: SessionCompletionPipeline? = null,
    private val announce: ProgressionAnnouncer? = null,
    private val estimateCalories: EstimateSessionCaloriesUseCase? = null,
    private val healthConnectSync: HealthConnectSyncScheduler? = null,
) {

    /**
     * @param origin how this finish was asked for. Defaults to [FinishOrigin.WORKOUT_SCREEN],
     *   the ordinary one: END was pressed on the workout screen and the recap that opens next
     *   is going to celebrate whatever came back. A caller that closes a session out with no
     *   recap to follow has to say so, because the payoff is then announced by the notification
     *   or by nothing at all — see [FinishOrigin]. Nothing else about the close-out reads it.
     * @param expected the session the caller means, or null to finish whichever one is open.
     */
    suspend operator fun invoke(
        origin: FinishOrigin = FinishOrigin.WORKOUT_SCREEN,
        expected: SessionRef? = null,
    ): Result<FinishedSession> {
        val outcome = closeOutUnderLock(expected)

        // Best-effort, and outside everything above: by here the workout is saved and its
        // payoff is banked, so a notification that could not be posted must not come back as a
        // finish that did not happen.
        //
        // Best-effort covers a *failure*, and a cancellation is not one — the distinction
        // `runCatching` cannot make on its own, and the one `SessionCompletionPipeline`'s own
        // step runner spells out. It matters more here than anywhere above: this is the last
        // thing `invoke` does, so a swallowed cancellation is a caller whose scope has already
        // gone being told the finish came back normally. `ActiveSessionViewModel` goes to the
        // trouble of rethrowing it around this very call for exactly that reason.
        outcome.getOrNull()?.progression?.let { progression ->
            val failure = runCatching { announce?.announce(progression, origin) }.exceptionOrNull()
            if (failure is CancellationException) throw failure
        }

        // Best-effort in the same way. Only a request — the write itself runs later, off this
        // path — so what could fail here is the queue, and a cancellation cannot arrive from it.
        outcome.getOrNull()?.let { finished ->
            runCatching { healthConnectSync?.requestSync(finished.summary.sessionId) }
        }

        return outcome
    }

    private suspend fun closeOutUnderLock(expected: SessionRef?): Result<FinishedSession> =
        lock.withTransition {
            val current = sessionStateRepository.observe().first()
            val sessionId = current.openSessionId
                ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

            sessionRepository.requireExpected(expected, sessionId)
                .getOrElse { return@withTransition Result.failure(it) }

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
                calories = session.calories ?: estimatedCalories(session.id, performed, now),
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

    /**
     * The estimate, or null when there is no estimator or it could not be made. A failure is
     * not a reason to leave the session open — see the class doc — but a cancellation is not a
     * failure, and is rethrown.
     */
    private suspend fun estimatedCalories(
        sessionId: Long,
        performed: List<WorkoutSet>,
        at: Long,
    ): Int? {
        val estimator = estimateCalories ?: return null
        return runCatching { estimator(sessionId, performed, at) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }
}
