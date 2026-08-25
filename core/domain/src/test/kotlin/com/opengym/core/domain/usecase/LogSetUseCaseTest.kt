package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.validation.SetValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSetUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val logSet = LogSetUseCase(sessions, state, clock)

    /** A plain working set of 5 at 80 kg, which every bound accepts. */
    private val draft = ActiveSet(reps = 5, weight = 80_000_000L)

    /** Starts a session with one exercise on it, and returns that exercise's row id. */
    private suspend fun startWithExercise(): Long = sessions.addExercise(startSession().getOrThrow().sessionId)

    @Test
    fun `logging a set writes it against its exercise`() =
        runTest {
            val exerciseId = startWithExercise()

            val logged = logSet(exerciseId, draft).getOrThrow()

            assertEquals(listOf(logged), sessions.getSets(exerciseId).first())
        }

    @Test
    fun `a logged set comes back with the id the store assigned`() =
        runTest {
            val exerciseId = startWithExercise()

            val logged = logSet(exerciseId, draft).getOrThrow()

            assertTrue("expected a real id, was ${logged.id}", logged.id > 0)
        }

    @Test
    fun `the set is completed and stamped with the clock, whatever the draft said`() =
        runTest {
            val exerciseId = startWithExercise()
            clock.advance(60_000L)

            // A draft that is explicitly *not* completed and carries a stale timestamp: both are
            // ignored, because this call is what completes the set.
            val logged = logSet(exerciseId, draft.copy(isCompleted = false, completedAt = null)).getOrThrow()

            assertEquals(true, logged.isCompleted)
            assertEquals(65_000L, logged.completedAt)
            assertEquals(65_000L, logged.createdAt)
            assertEquals(65_000L, logged.updatedAt)
        }

    @Test
    fun `every dimension of the draft is carried through`() =
        runTest {
            val exerciseId = startWithExercise()
            val full = ActiveSet(
                reps = 8,
                weight = 70_000_000L,
                duration = 45L,
                distance = 1_000L,
                heartRateAvg = 140,
                heartRateMax = 165,
                restAfter = 120L,
                rpe = 85,
                setType = SetType.DROP_SET,
                notes = "left elbow",
            )

            val logged = logSet(exerciseId, full).getOrThrow()

            assertEquals(8, logged.reps)
            assertEquals(70_000_000L, logged.weight)
            assertEquals(45L, logged.duration)
            assertEquals(1_000L, logged.distance)
            assertEquals(140, logged.heartRateAvg)
            assertEquals(165, logged.heartRateMax)
            assertEquals(120L, logged.restAfter)
            assertEquals(85, logged.rpe)
            assertEquals(SetType.DROP_SET, logged.setType)
            assertEquals("left elbow", logged.notes)
        }

    @Test
    fun `sets are numbered from one, in the order they are logged`() =
        runTest {
            val exerciseId = startWithExercise()

            val numbers = List(3) { logSet(exerciseId, draft).getOrThrow().setNumber }

            assertEquals(listOf(1, 2, 3), numbers)
        }

    @Test
    fun `numbering continues past the highest number already used, not the count`() =
        runTest {
            val exerciseId = startWithExercise()
            // Sets 1..3 exist, then set 2 is deleted the way #99 will delete one. The count is now
            // two, and a set numbered 3 would collide with the row that is still there.
            repeat(3) { logSet(exerciseId, draft).getOrThrow() }
            sessions.removeSet(exerciseId, setNumber = 2)

            val logged = logSet(exerciseId, draft).getOrThrow()

            assertEquals(4, logged.setNumber)
        }

    @Test
    fun `each exercise is numbered independently`() =
        runTest {
            val sessionId = startSession().getOrThrow().sessionId
            val first = sessions.addExercise(sessionId)
            val second = sessions.addExercise(sessionId)
            logSet(first, draft).getOrThrow()

            val logged = logSet(second, draft).getOrThrow()

            assertEquals(1, logged.setNumber)
        }

    @Test
    fun `a set that fails a bound is refused with the validator's own wording`() =
        runTest {
            val exerciseId = startWithExercise()
            val zeroReps = draft.copy(reps = 0)

            val result = logSet(exerciseId, zeroReps)

            assertEquals(
                SessionError.SetRejected(
                    "reps must be within ${SetValidator.REPS_MIN}..${SetValidator.REPS_MAX}, was 0",
                ),
                result.exceptionOrNull(),
            )
        }

    @Test
    fun `a refused set writes nothing`() =
        runTest {
            val exerciseId = startWithExercise()

            logSet(exerciseId, draft.copy(reps = 0))

            assertEquals(emptyList<WorkoutSet>(), sessions.getSets(exerciseId).first())
        }

    @Test
    fun `the exercise type is checked too, when it is given`() =
        runTest {
            val exerciseId = startWithExercise()
            // Reps are within bounds and a plank does not record them; a duration is what it owes.
            val result = logSet(exerciseId, draft, ExerciseType.ISOMETRIC)

            assertEquals(
                SessionError.SetRejected("a completed ISOMETRIC set must record a duration"),
                result.exceptionOrNull(),
            )
        }

    @Test
    fun `without an exercise type only the bounds are checked`() =
        runTest {
            val exerciseId = startWithExercise()

            val logged = logSet(exerciseId, ActiveSet(reps = 5)).getOrThrow()

            assertEquals(5, logged.reps)
            assertNull(logged.weight)
        }

    @Test
    fun `logging into an idle session is refused`() =
        runTest {
            val result = logSet(sessionExerciseId = 1L, draft = draft)

            assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
        }

    @Test
    fun `logging into a paused session is refused`() =
        runTest {
            val exerciseId = startWithExercise()
            val paused = pauseSession().getOrThrow()

            val result = logSet(exerciseId, draft)

            assertEquals(SessionError.SessionNotActive(paused), result.exceptionOrNull())
        }

    @Test
    fun `logging against an exercise of another session is refused`() =
        runTest {
            // A screen left behind by a session that has since been replaced: the row it names
            // exists and resolves, and belongs to a workout the user is not looking at.
            val stale = sessions.addExercise(sessionId = 99L)
            startWithExercise()

            val result = logSet(stale, draft)

            assertEquals(
                SessionError.ExerciseNotInSession(sessionExerciseId = stale, sessionId = 1L),
                result.exceptionOrNull(),
            )
        }

    @Test
    fun `a store that cannot be written fails rather than throwing`() =
        runTest {
            val exerciseId = startWithExercise()
            val failure = IllegalStateException("disk full")
            sessions.setInsertFailure = failure

            val result = logSet(exerciseId, draft)

            assertEquals(failure, result.exceptionOrNull())
        }

    @Test
    fun `logging does not touch the session row`() =
        runTest {
            // Deliberate: see the use case's note. The set's own `completedAt` and the draft are
            // what record that the session is being worked, and a write per set would re-emit
            // every observer of the session list for the length of a workout.
            val exerciseId = startWithExercise()

            logSet(exerciseId, draft).getOrThrow()

            assertEquals(0, sessions.updateCount)
        }
}
