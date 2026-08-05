package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import com.opengym.core.domain.model.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSessionUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)

    @Test
    fun `starting from idle returns an active state stamped with the current time`() = runTest {
        val started = startSession().getOrThrow()

        assertEquals(SessionState.Active(sessionId = 1L, startedAt = 5_000L), started)
    }

    @Test
    fun `starting from idle publishes the active state`() = runTest {
        startSession().getOrThrow()

        assertEquals(SessionState.Active(sessionId = 1L, startedAt = 5_000L), state.current)
    }

    @Test
    fun `starting persists an in-progress session with matching timestamps`() = runTest {
        val started = startSession().getOrThrow()

        val stored = sessions.stored(started.sessionId)
        assertEquals(
            WorkoutSession(
                id = 1L,
                templateId = null,
                completedAt = null,
                isInProgress = true,
                lastActiveAt = 5_000L,
                notes = null,
                createdAt = 5_000L,
                updatedAt = 5_000L,
            ),
            stored,
        )
    }

    @Test
    fun `starting carries the template and notes onto the session`() = runTest {
        val started = startSession(templateId = 42L, notes = "leg day").getOrThrow()

        val stored = sessions.stored(started.sessionId)
        assertEquals(42L, stored?.templateId)
        assertEquals("leg day", stored?.notes)
    }

    @Test
    fun `starting while another session is active is refused`() = runTest {
        startSession().getOrThrow()

        val result = startSession()

        assertEquals(SessionError.SessionAlreadyActive(sessionId = 1L), result.exceptionOrNull())
    }

    @Test
    fun `starting while another session is paused is refused`() = runTest {
        startSession().getOrThrow()
        PauseSessionUseCase(sessions, state, clock)().getOrThrow()

        val result = startSession()

        assertEquals(SessionError.SessionAlreadyActive(sessionId = 1L), result.exceptionOrNull())
    }

    @Test
    fun `a refused start writes nothing`() = runTest {
        startSession().getOrThrow()
        val statesBefore = state.recorded.size

        startSession()

        assertNull(sessions.stored(2L))
        assertEquals(statesBefore, state.recorded.size)
    }

    @Test
    fun `starting after the previous session finished is allowed`() = runTest {
        val summary = SessionSummary(
            sessionId = 1L,
            startedAt = 0L,
            finishedAt = 1L,
            durationMillis = 1L,
            exerciseCount = 1,
            totalSets = 1,
            totalVolume = 0L,
        )
        val finished = FakeSessionStateRepository(SessionState.Finished(summary))

        val started = StartSessionUseCase(sessions, finished, clock)().getOrThrow()

        assertEquals(SessionState.Active(sessionId = 1L, startedAt = 5_000L), started)
    }

    @Test
    fun `an in-progress row blocks a start even when the state machine reads idle`() = runTest {
        // What a process restart looks like: the row survives, the state does not.
        sessions.insert(
            WorkoutSession(isInProgress = true, lastActiveAt = 1L, createdAt = 1L, updatedAt = 1L),
        )

        val result = startSession()

        assertEquals(SessionError.SessionAlreadyActive(sessionId = 1L), result.exceptionOrNull())
    }

    @Test
    fun `a completed row does not block a start`() = runTest {
        sessions.insert(
            WorkoutSession(
                completedAt = 2L,
                isInProgress = false,
                lastActiveAt = 2L,
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )

        assertTrue(startSession().isSuccess)
    }

    @Test
    fun `the refusal surfaces as a descriptive IllegalStateException`() = runTest {
        startSession().getOrThrow()

        val refused = startSession()

        val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }

        assertEquals("cannot start a session while session 1 is still in progress", thrown.message)
    }

    @Test
    fun `the started session is the one getActive resolves to`() = runTest {
        val started = startSession().getOrThrow()

        assertEquals(started.sessionId, sessions.getActive().first()?.id)
    }
}
