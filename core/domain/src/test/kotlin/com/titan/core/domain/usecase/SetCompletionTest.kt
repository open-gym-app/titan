package com.titan.core.domain.usecase

import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.stats.SessionStatsCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking the tick off a set, and putting it back.
 *
 * Its own class rather than a section of [SetEditingTest], because it is a different question
 * about a set: that one is about the *values* and about a table whose numbers close up when a row
 * leaves it, and this is about a row that stays exactly where it is and stops counting.
 *
 * The figures are asserted here rather than left to the stats' own tests, because that is the
 * whole of what un-ticking a set is *for*: a set logged by accident that still fed the session's
 * volume would be a tick nobody had actually undone.
 */
class SetCompletionTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val logSet = LogSetUseCase(sessions, state, clock)
    private val markSetCompleted = MarkSetCompletedUseCase(sessions, state, clock)

    /** Starts a session with one exercise on it, and returns that exercise's row id. */
    private suspend fun startWithExercise(): Long = sessions.addExercise(startSession().getOrThrow().sessionId)

    /** The sets of [exerciseId] as they now stand, in the order they are read in. */
    private suspend fun setsOf(exerciseId: Long): List<WorkoutSet> =
        sessions.getSets(exerciseId).first().sortedBy(WorkoutSet::setNumber)

    @Test
    fun `un-ticking a set leaves it in the table, with its values`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(exerciseId, ActiveSet(reps = 8, weight = 70_000_000L)).getOrThrow()

            val edited = markSetCompleted(exerciseId, logged.id, isCompleted = false).getOrThrow()

            assertFalse(edited.isCompleted)
            assertEquals("this is not a deletion", listOf(edited), setsOf(exerciseId))
            assertEquals(logged.setNumber, edited.setNumber)
            assertEquals(8, edited.reps)
            assertEquals(70_000_000L, edited.weight)
        }

    @Test
    fun `an un-ticked set stops counting towards the session`() =
        runTest {
            val exerciseId = startWithExercise()
            logSet(exerciseId, ActiveSet(reps = 5, weight = 100_000_000L)).getOrThrow()
            val mistake = logSet(exerciseId, ActiveSet(reps = 5, weight = 100_000_000L)).getOrThrow()

            markSetCompleted(exerciseId, mistake.id, isCompleted = false).getOrThrow()

            val stats = SessionStatsCalculator.calculate(
                sets = setsOf(exerciseId),
                startedAt = 0L,
                endedAt = clock.nowMillis(),
            )
            assertEquals(1, stats.totalSets)
            assertEquals(500_000_000L, stats.totalVolume)
        }

    @Test
    fun `un-ticking does not move when the set was performed`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(exerciseId, ActiveSet(reps = 8, weight = 70_000_000L)).getOrThrow()
            clock.advance(600_000L)

            val edited = markSetCompleted(exerciseId, logged.id, isCompleted = false).getOrThrow()

            // The column says when the set was done, and noticing a stray tick ten minutes later is
            // not a claim about *when*. Only the column that exists to say a row was touched moves.
            assertEquals(logged.completedAt, edited.completedAt)
            assertEquals(logged.createdAt, edited.createdAt)
            assertEquals(605_000L, edited.updatedAt)
        }

    @Test
    fun `ticking it back on restores it without re-dating it`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(exerciseId, ActiveSet(reps = 8, weight = 70_000_000L)).getOrThrow()
            markSetCompleted(exerciseId, logged.id, isCompleted = false).getOrThrow()
            clock.advance(600_000L)

            val edited = markSetCompleted(exerciseId, logged.id, isCompleted = true).getOrThrow()

            assertTrue(edited.isCompleted)
            assertEquals("the set was performed when it was performed", logged.completedAt, edited.completedAt)
            assertEquals(listOf(edited), setsOf(exerciseId))
        }

    @Test
    fun `ticking off a set that was never performed stamps the clock for the first time`() =
        runTest {
            // A set a template laid down: in the table, under its number, with `completed_at = 0`
            // because it has not happened. Leaving the placeholder on the way in would file the set
            // at epoch 1970 in every aggregate that reads the column — see PERSISTENCE.md.
            val exerciseId = startWithExercise()
            val planned = sessions.addSet(
                exerciseId,
                WorkoutSet(
                    sessionExerciseId = exerciseId,
                    setNumber = 1,
                    reps = 8,
                    weight = 70_000_000L,
                    isCompleted = false,
                    completedAt = 0L,
                    createdAt = 5_000L,
                    updatedAt = 5_000L,
                ),
            )
            clock.advance(600_000L)

            val edited = markSetCompleted(exerciseId, planned.id, isCompleted = true).getOrThrow()

            assertTrue(edited.isCompleted)
            assertEquals(605_000L, edited.completedAt)
            assertEquals(605_000L, edited.updatedAt)
        }

    @Test
    fun `a tick that says what the set already says writes nothing`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(exerciseId, ActiveSet(reps = 8, weight = 70_000_000L)).getOrThrow()
            clock.advance(600_000L)

            val unchanged = markSetCompleted(exerciseId, logged.id, isCompleted = true).getOrThrow()

            // A second tap is not an edit. A write per tap would move `updatedAt` for a change nobody
            // could read back.
            assertEquals(logged, unchanged)
            assertEquals(logged.updatedAt, setsOf(exerciseId).single().updatedAt)
        }

    @Test
    fun `a set that no longer records its exercise's dimension cannot be ticked back on`() =
        runTest {
            val exerciseId = startWithExercise()
            // Logged with the bounds checked alone — no exercise type — so a set with no reps is a
            // set the store will take.
            val logged = logSet(exerciseId, ActiveSet(weight = 70_000_000L)).getOrThrow()
            markSetCompleted(exerciseId, logged.id, isCompleted = false).getOrThrow()

            val failure = markSetCompleted(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                isCompleted = true,
                exerciseType = ExerciseType.STRENGTH,
            ).exceptionOrNull()

            assertTrue("expected a rejection, was $failure", failure is SessionError.SetRejected)
            assertFalse("nothing was written", setsOf(exerciseId).single().isCompleted)
        }

    @Test
    fun `un-ticking is never refused for a dimension it is not claiming to have`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(exerciseId, ActiveSet(weight = 70_000_000L)).getOrThrow()

            // The same set, the same exercise type, the other direction: `SetValidator` asks nothing
            // of a set that is not claimed to have been performed, which is what makes the way out of
            // a mistaken tick always open.
            val edited = markSetCompleted(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                isCompleted = false,
                exerciseType = ExerciseType.STRENGTH,
            ).getOrThrow()

            assertFalse(edited.isCompleted)
        }

    @Test
    fun `a set of another exercise cannot be ticked through this one`() =
        runTest {
            val exerciseId = startWithExercise()
            val other = sessions.addExercise(ACTIVE_SESSION_ID_UNUSED_SESSION)
            val logged = logSet(exerciseId, ActiveSet(reps = 8, weight = 70_000_000L)).getOrThrow()

            val failure = markSetCompleted(other, logged.id, isCompleted = false).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.ExerciseNotInSession)
            assertTrue(setsOf(exerciseId).single().isCompleted)
        }

    @Test
    fun `a set id that is not this exercise's is refused`() =
        runTest {
            val exerciseId = startWithExercise()
            logSet(exerciseId, ActiveSet(reps = 8, weight = 70_000_000L)).getOrThrow()

            val failure = markSetCompleted(exerciseId, 4_242L, isCompleted = false).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.SetNotInExercise)
        }

    @Test
    fun `a paused session takes no ticks`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(exerciseId, ActiveSet(reps = 8, weight = 70_000_000L)).getOrThrow()
            pauseSession()

            val failure = markSetCompleted(exerciseId, logged.id, isCompleted = false).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.SessionNotActive)
            assertTrue(setsOf(exerciseId).single().isCompleted)
        }

    private companion object {
        /**
         * A session id nothing started, used to hang an exercise off a workout that is not the
         * running one. `FakeSessionRepository` numbers sessions from 1, so this cannot be one.
         */
        const val ACTIVE_SESSION_ID_UNUSED_SESSION = 99L
    }
}
