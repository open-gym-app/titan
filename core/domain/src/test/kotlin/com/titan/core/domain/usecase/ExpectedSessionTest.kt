package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.FakeInProgressSessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four lifecycle transitions, handed the session the caller means (#255).
 *
 * Each of them acts on whichever session the state machine has open, so a caller that only
 * *names* a session — a workout page opened from a link, a notification button pressed late —
 * has to say which one, and be refused when it is not the one open. The states are the ones the
 * report reproduced the damage in: the running workout paused, active, and forgotten by a process
 * restart, against a page for a session that does not exist and one for a session that is over.
 */
class ExpectedSessionTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val drafts = FakeInProgressSessionRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val resumeSession = ResumeSessionUseCase(sessions, state, clock)
    private val finishSession = FinishSessionUseCase(sessions, state, drafts, clock)
    private val discardSession = DiscardSessionUseCase(sessions, state, drafts)

    // --- the workout screen's half: the id alone ------------------------------

    @Test
    fun `a paused workout is not resumed for a session that does not exist`() =
        runTest {
            val running = startWithASet()
            pauseSession().getOrThrow()
            val paused = state.current

            val result = resumeSession(SessionRef(MISSING))

            assertEquals(SessionError.NotTheOpenSession(SessionRef(MISSING), running), result.exceptionOrNull())
            assertEquals(paused, state.current)
        }

    @Test
    fun `a forgotten workout is not adopted for a session that is over`() =
        runTest {
            val finished = finishedSession()
            val running = startWithASet()
            state.update(SessionState.Idle)

            val result = resumeSession(SessionRef(finished))

            assertTrue(result.exceptionOrNull() is SessionError.NotTheOpenSession)
            assertEquals(SessionState.Idle, state.current)
            assertTrue(sessions.stored(running)!!.isInProgress)
        }

    @Test
    fun `a running workout is not paused for another session`() =
        runTest {
            startWithASet()
            val active = state.current

            assertTrue(pauseSession(SessionRef(MISSING)).exceptionOrNull() is SessionError.NotTheOpenSession)
            assertEquals(active, state.current)
        }

    @Test
    fun `a running workout is not finished for another session`() =
        runTest {
            val finished = finishedSession()
            val running = startWithASet()

            listOf(SessionRef(MISSING), SessionRef(finished)).forEach { other ->
                assertTrue(finishSession(expected = other).exceptionOrNull() is SessionError.NotTheOpenSession)
            }

            assertTrue(sessions.stored(running)!!.isInProgress)
            assertTrue(state.current is SessionState.Active)
        }

    @Test
    fun `a paused workout is not discarded for another session`() =
        runTest {
            val finished = finishedSession()
            val running = startWithASet()
            pauseSession().getOrThrow()

            listOf(SessionRef(MISSING), SessionRef(finished)).forEach { other ->
                assertTrue(discardSession(other).exceptionOrNull() is SessionError.NotTheOpenSession)
            }

            assertNotNull(sessions.stored(running))
            assertEquals(1, sessions.storedSetCount(running))
            assertTrue(state.current is SessionState.Paused)
        }

    @Test
    fun `naming the open session changes nothing about the transitions`() =
        runTest {
            val running = startWithASet()
            val named = SessionRef(running)

            pauseSession(named).getOrThrow()
            resumeSession(named).getOrThrow()
            state.update(SessionState.Idle)
            resumeSession(named).getOrThrow()

            assertEquals(running, finishSession(expected = named).getOrThrow().summary.sessionId)
        }

    // --- the out-of-process half: the creation time ---------------------------

    @Test
    fun `a reused id with another creation time is not the session that was named`() =
        runTest {
            // A notification built for session 1 as it was, pressed after a restore wrote a
            // different workout back under the same number.
            val running = startWithASet()
            val stale = SessionRef(running, createdAt = STARTED_AT - 60_000L)

            assertTrue(finishSession(expected = stale).exceptionOrNull() is SessionError.NotTheOpenSession)
            assertTrue(discardSession(stale).exceptionOrNull() is SessionError.NotTheOpenSession)
            state.update(SessionState.Idle)
            assertTrue(resumeSession(stale).exceptionOrNull() is SessionError.NotTheOpenSession)

            assertTrue(sessions.stored(running)!!.isInProgress)
        }

    @Test
    fun `the id and the creation time together resume a forgotten workout`() =
        runTest {
            val running = startWithASet()
            state.update(SessionState.Idle)

            val resumed = resumeSession(SessionRef.of(sessions.stored(running)!!)).getOrThrow()

            assertEquals(running, resumed.sessionId)
        }

    // --- a discard offered only for an empty workout --------------------------

    @Test
    fun `a discard offered for an empty workout is refused once a set is logged`() =
        runTest {
            val running = startWithASet()

            val result = discardSession(SessionRef(running), onlyIfNothingLogged = true)

            assertEquals(SessionError.SessionHasLoggedSets(running, setCount = 1), result.exceptionOrNull())
            assertNotNull(sessions.stored(running))
        }

    @Test
    fun `sets planned but not done do not stop the empty discard`() =
        runTest {
            val running = startSession().getOrThrow().sessionId
            sessions.addSet(sessions.addExercise(running), isCompleted = false)

            discardSession(SessionRef(running), onlyIfNothingLogged = true).getOrThrow()

            assertNull(sessions.stored(running))
        }

    // --- helpers ----------------------------------------------------------------

    /** Starts a workout with one completed set, the way a lifter mid-session has one. */
    private suspend fun startWithASet(): Long {
        clock.now = STARTED_AT
        val sessionId = startSession().getOrThrow().sessionId
        sessions.addSet(sessions.addExercise(sessionId))
        return sessionId
    }

    /** A workout from last week, closed out and on disk. */
    private suspend fun finishedSession(): Long =
        sessions.insert(
            WorkoutSession(
                completedAt = 2_000L,
                isInProgress = false,
                lastActiveAt = 2_000L,
                createdAt = 1_000L,
                updatedAt = 2_000L,
            ),
        )

    private suspend fun FakeSessionRepository.storedSetCount(sessionId: Long): Int =
        getSetsForSession(sessionId).first().size

    private companion object {
        /** No row has this id. */
        const val MISSING = 999L

        const val STARTED_AT = 10_000L
    }
}
