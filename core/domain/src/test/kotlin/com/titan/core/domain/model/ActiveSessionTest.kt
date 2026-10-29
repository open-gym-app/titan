package com.titan.core.domain.model

import com.titan.core.domain.timer.RestTimerSnapshot
import com.titan.core.domain.timer.RestTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a draft says about the workout it captures — the derived readings a restoring
 * caller needs, and the invariants that stop an incoherent one from existing.
 *
 * The wire format is in [ActiveSessionSerializationTest]; note that the invariants
 * asserted here are what make a *decoded* draft trustworthy too, since the deserializer
 * calls the same constructor.
 */
class ActiveSessionTest {

    @Test
    fun `elapsed time is wall-clock time less the paused stretches`() {
        val session = minimalSession().copy(pausedDuration = 2.minutes)

        assertEquals(8.minutes, session.elapsedAt(STARTED_AT + 10.minutes.inWholeMilliseconds))
    }

    @Test
    fun `a paused session stops accruing elapsed time`() {
        val session = minimalSession().copy(pausedAt = STARTED_AT + 5.minutes.inWholeMilliseconds)

        assertEquals(5.minutes, session.elapsedAt(STARTED_AT + 5.minutes.inWholeMilliseconds))
        assertEquals(5.minutes, session.elapsedAt(STARTED_AT + 90.minutes.inWholeMilliseconds))
    }

    @Test
    fun `a paused session's elapsed time still excludes its earlier pauses`() {
        val session = minimalSession().copy(
            pausedDuration = 2.minutes,
            pausedAt = STARTED_AT + 10.minutes.inWholeMilliseconds,
        )

        assertEquals(8.minutes, session.elapsedAt(STARTED_AT + 90.minutes.inWholeMilliseconds))
    }

    @Test
    fun `a clock that stepped backwards floors elapsed time at zero`() {
        val session = minimalSession()

        assertEquals(Duration.ZERO, session.elapsedAt(STARTED_AT - 60_000))
    }

    @Test
    fun `a running draft restores as an active session`() {
        val session = minimalSession()

        assertEquals(SessionState.Active(sessionId = 1, startedAt = STARTED_AT), session.toSessionState())
        assertFalse(session.isPaused)
    }

    @Test
    fun `a paused draft restores as a paused session`() {
        val pausedAt = STARTED_AT + 60_000
        val session = minimalSession().copy(pausedAt = pausedAt)

        assertEquals(SessionState.Paused(sessionId = 1, pausedAt = pausedAt), session.toSessionState())
        assertTrue(session.isPaused)
    }

    @Test
    fun `the current exercise is the one the index points at`() {
        val session = maximalSession()

        assertEquals(session.exercises[1], session.currentExercise)
    }

    @Test
    fun `a session with no exercises has no current exercise`() {
        assertNull(minimalSession().currentExercise)
    }

    @Test
    fun `completed sets exclude the ones the user has not ticked off`() {
        val session = maximalSession()

        assertEquals(listOf(maximalSet()), session.completedSets)
    }

    @Test
    fun `the rest timer is resolved against the time of the read`() {
        val session = minimalSession().copy(
            restTimer = RestTimerSnapshot.Running(deadline = STARTED_AT + 90_000, total = 90.seconds),
        )

        assertEquals(
            RestTimerState.Running(30.seconds, 90.seconds),
            session.restTimerStateAt(STARTED_AT + 60_000),
        )
    }

    @Test
    fun `a draft read after its rest ran out restores a finished rest`() {
        val session = minimalSession().copy(
            restTimer = RestTimerSnapshot.Running(deadline = STARTED_AT + 90_000, total = 90.seconds),
        )

        assertEquals(RestTimerState.Finished, session.restTimerStateAt(STARTED_AT + 10.minutes.inWholeMilliseconds))
    }

    @Test
    fun `a draft without a session row is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            minimalSession().copy(sessionId = 0)
        }
    }

    @Test
    fun `a negative paused duration is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            minimalSession().copy(pausedDuration = (-1).seconds)
        }
    }

    @Test
    fun `a pause that predates the session start is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            minimalSession().copy(pausedAt = STARTED_AT - 1)
        }
    }

    @Test
    fun `an index past the end of the exercise list is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            minimalSession().copy(
                exercises = listOf(ActiveExercise(exerciseId = 1)),
                currentExerciseIndex = 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            minimalSession().copy(currentExerciseIndex = -1)
        }
        // Zero is the only index an empty list tolerates: it is how an older draft said "none".
        assertThrows(IllegalArgumentException::class.java) {
            minimalSession().copy(currentExerciseIndex = 1)
        }
    }

    @Test
    fun `a draft names no exercise unless told one`() {
        assertNull(minimalSession().currentExerciseIndex)
        assertNull(minimalSession().currentExercise)
    }

    @Test
    fun `no exercise is not the first exercise`() {
        // The distinction the field is nullable for: a rest opened from the session clock
        // follows no set, and a restore must not credit it to whichever exercise is first.
        val session = minimalSession().copy(exercises = listOf(ActiveExercise(exerciseId = 1)))

        assertNull(session.currentExercise)
        assertEquals(1L, session.copy(currentExerciseIndex = 0).currentExercise?.exerciseId)
    }

    @Test
    fun `an index of zero is still allowed on a session with no exercises yet`() {
        // How a draft written while the field was a plain integer spelled "none yet".
        assertNull(minimalSession().copy(currentExerciseIndex = 0).currentExercise)
    }

    @Test
    fun `a rest is due after a standalone exercise but not inside a superset`() {
        // The draft is what the live session reads, so the grouping has to be legible
        // from it alone — see ActiveExercise.supersetGroup.
        val session = minimalSession().copy(
            exercises = listOf(
                ActiveExercise(exerciseId = 1, supersetGroup = 0),
                ActiveExercise(exerciseId = 2, supersetGroup = 0),
                ActiveExercise(exerciseId = 3),
            ),
        )

        assertFalse(session.isRestDueAfter(0))
        assertTrue(session.isRestDueAfter(1))
        assertTrue(session.isRestDueAfter(2))
    }

    @Test
    fun `a session with no exercises is asked about rest and answers`() {
        assertTrue(minimalSession().isRestDueAfter(0))
    }

    @Test
    fun `an exercise without a catalogue entry is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ActiveExercise(exerciseId = 0) }
    }

    @Test
    fun `a set ticked off without a completion time is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ActiveSet(reps = 5, isCompleted = true) }
    }

    @Test
    fun `a set carrying a completion time it has not reached yet is allowed`() {
        val typed = ActiveSet(reps = 5, completedAt = STARTED_AT)

        assertFalse(typed.isCompleted)
    }

    @Test
    fun `a set with an out-of-range rpe is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ActiveSet(rpe = WorkoutSet.RPE_MAX + 1) }
        assertThrows(IllegalArgumentException::class.java) { ActiveSet(rpe = WorkoutSet.RPE_MIN - 1) }
    }
}
