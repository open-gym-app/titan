package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import com.opengym.core.domain.model.WorkoutSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeSessionUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val resumeSession = ResumeSessionUseCase(sessions, state, clock)

    @Test
    fun `resuming a paused session returns it to active`() = runTest {
        startSession().getOrThrow()
        pauseSession().getOrThrow()
        clock.advance(60_000L)

        val resumed = resumeSession().getOrThrow()

        assertEquals(SessionState.Active(sessionId = 1L, startedAt = 5_000L), resumed)
    }

    @Test
    fun `resuming preserves the original start time rather than restarting it`() = runTest {
        val started = startSession().getOrThrow()
        pauseSession().getOrThrow()
        clock.advance(3_600_000L)

        val resumed = resumeSession().getOrThrow()

        assertEquals(started.startedAt, resumed.startedAt)
    }

    @Test
    fun `resuming publishes the active state`() = runTest {
        startSession().getOrThrow()
        pauseSession().getOrThrow()

        resumeSession().getOrThrow()

        assertEquals(SessionState.Active(sessionId = 1L, startedAt = 5_000L), state.current)
    }

    @Test
    fun `resuming stamps last active at`() = runTest {
        val started = startSession().getOrThrow()
        pauseSession().getOrThrow()
        clock.advance(60_000L)

        resumeSession().getOrThrow()

        val stored = sessions.stored(started.sessionId)
        assertEquals(65_000L, stored?.lastActiveAt)
        assertEquals(65_000L, stored?.updatedAt)
        assertEquals(5_000L, stored?.createdAt)
    }

    @Test
    fun `resuming with no session in progress is refused`() = runTest {
        val result = resumeSession()

        assertEquals(SessionError.SessionNotPaused(SessionState.Idle), result.exceptionOrNull())
    }

    @Test
    fun `resuming adopts a session left in progress by a lost state machine`() = runTest {
        // What a process restart looks like: the row survives, the state does not.
        val restarted = FakeSessionStateRepository()
        sessions.insert(
            WorkoutSession(isInProgress = true, lastActiveAt = 100L, createdAt = 100L, updatedAt = 100L),
        )

        val resumed = ResumeSessionUseCase(sessions, restarted, clock)().getOrThrow()

        assertEquals(SessionState.Active(sessionId = 1L, startedAt = 100L), resumed)
        assertEquals(resumed, restarted.current)
    }

    @Test
    fun `an adopted session keeps its original start time`() = runTest {
        val restarted = FakeSessionStateRepository()
        sessions.insert(
            WorkoutSession(isInProgress = true, lastActiveAt = 100L, createdAt = 100L, updatedAt = 100L),
        )

        val resumed = ResumeSessionUseCase(sessions, restarted, clock)().getOrThrow()

        assertEquals(100L, resumed.startedAt)
        assertEquals(5_000L, sessions.stored(1L)?.lastActiveAt)
    }

    @Test
    fun `a completed session is not adopted`() = runTest {
        sessions.insert(
            WorkoutSession(
                completedAt = 200L,
                isInProgress = false,
                lastActiveAt = 200L,
                createdAt = 100L,
                updatedAt = 200L,
            ),
        )

        val result = resumeSession()

        assertEquals(SessionError.SessionNotPaused(SessionState.Idle), result.exceptionOrNull())
    }

    @Test
    fun `resuming an already active session is refused`() = runTest {
        val started = startSession().getOrThrow()

        val result = resumeSession()

        assertEquals(SessionError.SessionNotPaused(started), result.exceptionOrNull())
    }

    @Test
    fun `resuming from finished with no session in progress is refused`() = runTest {
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

        val result = ResumeSessionUseCase(sessions, stateRepository, clock)()

        assertEquals(SessionError.SessionNotPaused(finished), result.exceptionOrNull())
    }

    @Test
    fun `a refused resume writes nothing`() = runTest {
        val result = resumeSession()

        assertTrue(result.isFailure)
        assertEquals(0, sessions.updateCount)
        assertEquals(SessionState.Idle, state.current)
    }

    @Test
    fun `resuming a session whose row has been deleted is refused`() = runTest {
        val started = startSession().getOrThrow()
        pauseSession().getOrThrow()
        sessions.delete(started.sessionId)

        val result = resumeSession()

        assertEquals(SessionError.SessionNotFound(sessionId = 1L), result.exceptionOrNull())
    }

    @Test
    fun `the refusal surfaces as a descriptive IllegalStateException`() = runTest {
        val refused = resumeSession()

        val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }

        assertEquals("expected a paused session, but the current state is Idle", thrown.message)
    }

    @Test
    fun `pause and resume can cycle repeatedly`() = runTest {
        startSession().getOrThrow()

        repeat(3) {
            clock.advance(1_000L)
            pauseSession().getOrThrow()
            clock.advance(1_000L)
            resumeSession().getOrThrow()
        }

        assertEquals(SessionState.Active(sessionId = 1L, startedAt = 5_000L), state.current)
        assertEquals(11_000L, sessions.stored(1L)?.lastActiveAt)
    }
}
