package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishSessionUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val finishSession = FinishSessionUseCase(sessions, state, clock)

    /** Starts a session with one exercise carrying [sets] completed sets, and returns its id. */
    private suspend fun startWithSets(sets: Int = 1): Long {
        val started = startSession().getOrThrow()
        val exerciseId = sessions.addExercise(started.sessionId)
        repeat(sets) { sessions.addSet(exerciseId) }
        return started.sessionId
    }

    @Test
    fun `finishing an active session with logged sets succeeds`() = runTest {
        startWithSets()
        clock.advance(60_000L)

        val finished = finishSession().getOrThrow()

        assertEquals(
            SessionSummary(
                sessionId = 1L,
                startedAt = 5_000L,
                finishedAt = 65_000L,
                durationMillis = 60_000L,
                exerciseCount = 1,
                totalSets = 1,
                totalVolume = 500_000L,
            ),
            finished.summary,
        )
    }

    @Test
    fun `finishing publishes the finished state`() = runTest {
        startWithSets()

        val finished = finishSession().getOrThrow()

        assertEquals(finished, state.current)
    }

    @Test
    fun `finishing closes the session row out`() = runTest {
        val sessionId = startWithSets()
        clock.advance(60_000L)

        finishSession().getOrThrow()

        val stored = sessions.stored(sessionId)
        assertEquals(false, stored?.isInProgress)
        assertEquals(65_000L, stored?.completedAt)
        assertEquals(65_000L, stored?.lastActiveAt)
        assertEquals(65_000L, stored?.updatedAt)
    }

    @Test
    fun `a finished session no longer resolves as active`() = runTest {
        startWithSets()

        finishSession().getOrThrow()

        assertNull(sessions.getActive().first())
    }

    @Test
    fun `a paused session can be finished`() = runTest {
        startWithSets()
        pauseSession().getOrThrow()

        val finished = finishSession().getOrThrow()

        assertEquals(1L, finished.summary.sessionId)
    }

    @Test
    fun `duration spans the paused stretch`() = runTest {
        startWithSets()
        clock.advance(60_000L)
        pauseSession().getOrThrow()
        clock.advance(600_000L)

        val finished = finishSession().getOrThrow()

        assertEquals(660_000L, finished.summary.durationMillis)
    }

    @Test
    fun `a session with no exercises at all is refused`() = runTest {
        startSession().getOrThrow()

        val result = finishSession()

        assertEquals(SessionError.NoSetsLogged, result.exceptionOrNull())
    }

    @Test
    fun `a session whose exercises have no sets is refused`() = runTest {
        val started = startSession().getOrThrow()
        sessions.addExercise(started.sessionId)

        val result = finishSession()

        assertEquals(SessionError.NoSetsLogged, result.exceptionOrNull())
    }

    @Test
    fun `a session holding only uncompleted placeholder sets is refused`() = runTest {
        val started = startSession().getOrThrow()
        val exerciseId = sessions.addExercise(started.sessionId)
        sessions.addSet(exerciseId, isCompleted = false)

        val result = finishSession()

        assertEquals(SessionError.NoSetsLogged, result.exceptionOrNull())
    }

    @Test
    fun `a refused finish leaves the session in progress and the state untouched`() = runTest {
        val started = startSession().getOrThrow()
        val updatesBefore = sessions.updateCount

        val result = finishSession()

        assertTrue(result.isFailure)
        assertEquals(true, sessions.stored(started.sessionId)?.isInProgress)
        assertEquals(updatesBefore, sessions.updateCount)
        assertEquals(started, state.current)
    }

    @Test
    fun `finishing an idle session is refused`() = runTest {
        val result = finishSession()

        assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
    }

    @Test
    fun `finishing an already finished session is refused`() = runTest {
        startWithSets()
        val finished = finishSession().getOrThrow()

        val result = finishSession()

        assertEquals(SessionError.SessionNotActive(finished), result.exceptionOrNull())
    }

    @Test
    fun `finishing a session whose row has been deleted is refused`() = runTest {
        val sessionId = startWithSets()
        sessions.delete(sessionId)

        val result = finishSession()

        assertEquals(SessionError.SessionNotFound(sessionId = 1L), result.exceptionOrNull())
    }

    @Test
    fun `the no-sets refusal surfaces as a descriptive IllegalStateException`() = runTest {
        startSession().getOrThrow()

        val refused = finishSession()

        val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }
        assertEquals("cannot finish a session with no completed sets", thrown.message)
    }

    @Test
    fun `only completed sets are counted`() = runTest {
        val started = startSession().getOrThrow()
        val exerciseId = sessions.addExercise(started.sessionId)
        sessions.addSet(exerciseId, reps = 10, weight = 50_000L, isCompleted = true)
        sessions.addSet(exerciseId, reps = 10, weight = 50_000L, isCompleted = false)

        val summary = finishSession().getOrThrow().summary

        assertEquals(1, summary.totalSets)
        assertEquals(500_000L, summary.totalVolume)
    }

    @Test
    fun `volume and set count sum across exercises`() = runTest {
        val started = startSession().getOrThrow()
        val squat = sessions.addExercise(started.sessionId, exerciseId = 1L)
        val bench = sessions.addExercise(started.sessionId, exerciseId = 2L)
        repeat(3) { sessions.addSet(squat, reps = 5, weight = 100_000L) }
        repeat(2) { sessions.addSet(bench, reps = 8, weight = 60_000L) }

        val summary = finishSession().getOrThrow().summary

        assertEquals(2, summary.exerciseCount)
        assertEquals(5, summary.totalSets)
        assertEquals(3 * 5 * 100_000L + 2 * 8 * 60_000L, summary.totalVolume)
    }

    @Test
    fun `exercises with no completed sets are excluded from the exercise count`() = runTest {
        val started = startSession().getOrThrow()
        val squat = sessions.addExercise(started.sessionId, exerciseId = 1L)
        val skipped = sessions.addExercise(started.sessionId, exerciseId = 2L)
        sessions.addSet(squat)
        sessions.addSet(skipped, isCompleted = false)

        val summary = finishSession().getOrThrow().summary

        assertEquals(1, summary.exerciseCount)
    }

    @Test
    fun `sets without weight or reps count as zero volume rather than being dropped`() = runTest {
        val started = startSession().getOrThrow()
        val plank = sessions.addExercise(started.sessionId)
        sessions.addSet(plank, reps = null, weight = null)

        val summary = finishSession().getOrThrow().summary

        assertEquals(1, summary.totalSets)
        assertEquals(0L, summary.totalVolume)
    }

    @Test
    fun `summarising reads the session's sets once, not once per exercise`() = runTest {
        val started = startSession().getOrThrow()
        repeat(5) { sessions.addSet(sessions.addExercise(started.sessionId, exerciseId = it + 1L)) }

        finishSession().getOrThrow()

        assertEquals(1, sessions.setsForSessionReads)
        assertEquals(0, sessions.setsByExerciseReads)
    }

    @Test
    fun `a backwards clock adjustment cannot produce a negative duration`() = runTest {
        startWithSets()
        clock.now = 1_000L

        val summary = finishSession().getOrThrow().summary

        assertEquals(0L, summary.durationMillis)
    }
}
