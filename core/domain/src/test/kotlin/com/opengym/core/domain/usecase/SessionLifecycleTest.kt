package com.opengym.core.domain.usecase

import app.cash.turbine.test
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.FakeInProgressSessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine end to end, as opposed to the per-use-case tests, which each
 * cover one transition and its refusals.
 */
class SessionLifecycleTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val drafts = FakeInProgressSessionRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val resumeSession = ResumeSessionUseCase(sessions, state, clock)
    private val finishSession = FinishSessionUseCase(sessions, state, drafts, clock)

    @Test
    fun `a full start-pause-resume-finish run walks every state in order`() =
        runTest {
            val started = startSession().getOrThrow()
            val exerciseId = sessions.addExercise(started.sessionId)
            sessions.addSet(exerciseId)

            clock.advance(60_000L)
            pauseSession().getOrThrow()
            clock.advance(60_000L)
            resumeSession().getOrThrow()
            clock.advance(60_000L)
            val finished = finishSession().getOrThrow()

            assertEquals(
                listOf(
                    SessionState.Idle,
                    SessionState.Active(sessionId = 1L, startedAt = 5_000L),
                    SessionState.Paused(sessionId = 1L, pausedAt = 65_000L),
                    SessionState.Active(sessionId = 1L, startedAt = 5_000L),
                    finished.state,
                ),
                state.recorded,
            )
        }

    @Test
    fun `observers see each transition as it happens`() =
        runTest {
            state.observe().test {
                assertEquals(SessionState.Idle, awaitItem())

                val started = startSession().getOrThrow()
                assertEquals(started, awaitItem())

                val paused = pauseSession().getOrThrow()
                assertEquals(paused, awaitItem())

                val resumed = resumeSession().getOrThrow()
                assertEquals(resumed, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a new session can be started once the previous one is finished`() =
        runTest {
            val first = startSession().getOrThrow()
            sessions.addSet(sessions.addExercise(first.sessionId))
            finishSession().getOrThrow()

            val second = startSession().getOrThrow()

            assertEquals(2L, second.sessionId)
            assertEquals(false, sessions.stored(first.sessionId)?.isInProgress)
            assertEquals(true, sessions.stored(second.sessionId)?.isInProgress)
        }

    @Test
    fun `a session survives losing the state machine and can still be finished`() =
        runTest {
            // Regression: blocking a duplicate start must not strand the open session.
            // Before resume learned to adopt it, every transition here was refused and
            // the workout became unreachable.
            val started = startSession().getOrThrow()
            sessions.addSet(sessions.addExercise(started.sessionId))

            // The process restarts: the row survives, the in-memory state does not.
            val restarted = FakeSessionStateRepository()
            assertTrue(StartSessionUseCase(sessions, restarted, clock)().isFailure)

            clock.advance(60_000L)
            val resumed = ResumeSessionUseCase(sessions, restarted, clock)().getOrThrow()
            assertEquals(started, resumed)

            val finished = FinishSessionUseCase(sessions, restarted, drafts, clock)().getOrThrow()
            assertEquals(started.sessionId, finished.summary.sessionId)
            assertEquals(1, finished.summary.totalSets)
            assertEquals(false, sessions.stored(started.sessionId)?.isInProgress)
        }

    @Test
    fun `once the stranded session is finished a new one can start`() =
        runTest {
            val started = startSession().getOrThrow()
            sessions.addSet(sessions.addExercise(started.sessionId))

            val restarted = FakeSessionStateRepository()
            ResumeSessionUseCase(sessions, restarted, clock)().getOrThrow()
            FinishSessionUseCase(sessions, restarted, drafts, clock)().getOrThrow()

            val second = StartSessionUseCase(sessions, restarted, clock)().getOrThrow()

            assertEquals(2L, second.sessionId)
        }

    @Test
    fun `a refused transition leaves the machine in its previous state`() =
        runTest {
            val started = startSession().getOrThrow()

            // Every transition that is illegal from Active.
            assertTrue(startSession().isFailure)
            assertTrue(resumeSession().isFailure)
            assertTrue(finishSession().isFailure)

            assertEquals(started, state.current)
            assertEquals(listOf<SessionState>(SessionState.Idle, started), state.recorded)
        }
}
