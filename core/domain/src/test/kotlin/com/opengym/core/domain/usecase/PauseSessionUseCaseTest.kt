package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseSessionUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)

    @Test
    fun `pausing an active session returns a paused state stamped with the current time`() =
        runTest {
            startSession().getOrThrow()
            clock.advance(60_000L)

            val paused = pauseSession().getOrThrow()

            assertEquals(SessionState.Paused(sessionId = 1L, pausedAt = 65_000L), paused)
        }

    @Test
    fun `pausing publishes the paused state`() =
        runTest {
            startSession().getOrThrow()

            pauseSession().getOrThrow()

            assertEquals(SessionState.Paused(sessionId = 1L, pausedAt = 5_000L), state.current)
        }

    @Test
    fun `pausing stamps last active at without ending the session`() =
        runTest {
            val started = startSession().getOrThrow()
            clock.advance(60_000L)

            pauseSession().getOrThrow()

            val stored = sessions.stored(started.sessionId)
            assertEquals(65_000L, stored?.lastActiveAt)
            assertEquals(65_000L, stored?.updatedAt)
            assertEquals(true, stored?.isInProgress)
            assertEquals(null, stored?.completedAt)
        }

    @Test
    fun `pausing an idle session is refused`() =
        runTest {
            val result = pauseSession()

            assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
        }

    @Test
    fun `pausing an already paused session is refused`() =
        runTest {
            startSession().getOrThrow()
            val paused = pauseSession().getOrThrow()

            val result = pauseSession()

            assertEquals(SessionError.SessionNotActive(paused), result.exceptionOrNull())
        }

    @Test
    fun `pausing a finished session is refused`() =
        runTest {
            val summary = SessionSummary(
                sessionId = 1L,
                startedAt = 0L,
                finishedAt = 1L,
                durationMillis = 1L,
                exerciseCount = 1,
                totalSets = 1,
                totalVolume = 0L,
            )
            val finished = SessionState.Finished(summary)
            val stateRepository = FakeSessionStateRepository(finished)

            val result = PauseSessionUseCase(sessions, stateRepository, clock)()

            assertEquals(SessionError.SessionNotActive(finished), result.exceptionOrNull())
        }

    @Test
    fun `a refused pause writes nothing`() =
        runTest {
            val result = pauseSession()

            assertTrue(result.isFailure)
            assertEquals(0, sessions.updateCount)
            assertEquals(SessionState.Idle, state.current)
        }

    @Test
    fun `pausing a session whose row has been deleted is refused`() =
        runTest {
            val started = startSession().getOrThrow()
            sessions.delete(started.sessionId)

            val result = pauseSession()

            assertEquals(SessionError.SessionNotFound(sessionId = 1L), result.exceptionOrNull())
        }

    @Test
    fun `the refusal surfaces as a descriptive IllegalStateException`() =
        runTest {
            val refused = pauseSession()

            val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }

            assertEquals("expected an active session, but the current state is Idle", thrown.message)
        }
}
