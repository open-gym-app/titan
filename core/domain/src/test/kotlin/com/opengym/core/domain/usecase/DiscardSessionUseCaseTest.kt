package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.FakeInProgressSessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscardSessionUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val drafts = FakeInProgressSessionRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val discardSession = DiscardSessionUseCase(sessions, state, drafts)

    /** Starts a session with one exercise and one logged set, and returns its id. */
    private suspend fun startWithSets(): Long {
        val started = startSession().getOrThrow()
        sessions.addSet(sessions.addExercise(started.sessionId))
        drafts.save(ActiveSession(sessionId = started.sessionId, startedAt = started.startedAt))
        return started.sessionId
    }

    @Test
    fun `discarding an active session reports which one went`() = runTest {
        val sessionId = startWithSets()

        assertEquals(sessionId, discardSession().getOrThrow())
    }

    @Test
    fun `discarding deletes the session row`() = runTest {
        val sessionId = startWithSets()

        discardSession().getOrThrow()

        assertNull(sessions.stored(sessionId))
    }

    @Test
    fun `discarding takes the session's sets with it`() = runTest {
        val sessionId = startWithSets()

        discardSession().getOrThrow()

        assertEquals(emptyList<WorkoutSet>(), sessions.getSetsForSession(sessionId).first())
    }

    @Test
    fun `discarding clears the draft`() = runTest {
        startWithSets()

        discardSession().getOrThrow()

        assertNull(drafts.load())
    }

    @Test
    fun `discarding puts the state machine back to idle`() = runTest {
        startWithSets()

        discardSession().getOrThrow()

        assertEquals(SessionState.Idle, state.current)
    }

    @Test
    fun `a discarded session does not block the next one`() = runTest {
        startWithSets()
        discardSession().getOrThrow()

        val second = startSession().getOrThrow()

        assertEquals(2L, second.sessionId)
    }

    @Test
    fun `a paused session can be discarded`() = runTest {
        val sessionId = startWithSets()
        pauseSession().getOrThrow()

        assertEquals(sessionId, discardSession().getOrThrow())
    }

    @Test
    fun `a session with nothing logged can be discarded, which finishing refuses`() = runTest {
        // The pair of behaviours that makes this use case necessary: finish is strict about
        // an empty session, so without this the user is stuck inside one.
        val started = startSession().getOrThrow()
        val finish = FinishSessionUseCase(sessions, state, drafts, clock)

        assertEquals(SessionError.NoSetsLogged, finish().exceptionOrNull())
        assertEquals(started.sessionId, discardSession().getOrThrow())
    }

    @Test
    fun `discarding when nothing is running is refused`() = runTest {
        val result = discardSession()

        assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
    }

    @Test
    fun `discarding a finished session is refused`() = runTest {
        val finished = SessionState.Finished(
            SessionSummary(
                sessionId = 1L,
                startedAt = 0L,
                finishedAt = 1L,
                durationMillis = 1L,
                exerciseCount = 1,
                totalSets = 1,
                totalVolume = 0L,
            ),
        )

        val result = DiscardSessionUseCase(sessions, FakeSessionStateRepository(finished), drafts)()

        assertEquals(SessionError.SessionNotActive(finished), result.exceptionOrNull())
    }

    @Test
    fun `a refused discard writes nothing`() = runTest {
        val result = discardSession()

        assertTrue(result.isFailure)
        assertEquals(0, drafts.clearCount)
        assertEquals(SessionState.Idle, state.current)
    }

    @Test
    fun `a delete that fails leaves the session running rather than stranding the row`() = runTest {
        val sessionId = startWithSets()
        val failure = IllegalStateException("disk full")
        sessions.deleteFailure = failure

        val result = discardSession()

        assertEquals(failure, result.exceptionOrNull())
        assertEquals(SessionState.Active(sessionId = sessionId, startedAt = 5_000L), state.current)
        assertEquals(0, drafts.clearCount)
    }

    @Test
    fun `the refusal surfaces as a descriptive IllegalStateException`() = runTest {
        val refused = discardSession()

        val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }

        assertEquals("expected an active session, but the current state is Idle", thrown.message)
    }
}
