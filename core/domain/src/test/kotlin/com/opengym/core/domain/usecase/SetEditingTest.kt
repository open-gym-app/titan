package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.WorkoutSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correcting a set, deleting one, and putting one back.
 *
 * One class for the three because they are one feature: a table that can be corrected is a table
 * whose numbers have to close up when a row leaves and open again when it comes back, and the
 * three use cases are only right *together*. Testing the delete without the undo would leave the
 * one property that matters — that a set survives a mistake — asserted nowhere.
 */
class SetEditingTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val logSet = LogSetUseCase(sessions, state, clock)
    private val editSet = EditSetUseCase(sessions, state, clock)
    private val deleteSet = DeleteSetUseCase(sessions, state, clock)
    private val restoreSet = RestoreSetUseCase(sessions, state, clock)

    /** Starts a session with one exercise on it, and returns that exercise's row id. */
    private suspend fun startWithExercise(): Long = sessions.addExercise(startSession().getOrThrow().sessionId)

    /** Logs [count] plain working sets against [exerciseId], and returns them in order. */
    private suspend fun logSets(
        exerciseId: Long,
        count: Int,
    ): List<WorkoutSet> =
        (1..count).map { number ->
            logSet(exerciseId, ActiveSet(reps = 5 + number, weight = 60_000_000L)).getOrThrow()
        }

    /** The numbers on [exerciseId]'s table as it now stands, in the order they are read in. */
    private suspend fun numbersOf(exerciseId: Long): List<Int> =
        sessions.getSets(exerciseId).first().sortedBy(WorkoutSet::setNumber).map(WorkoutSet::setNumber)

    // ─── Editing ────────────────────────────────────────────────────────────────────────

    @Test
    fun `an edit writes the new values over the set`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSets(exerciseId, count = 1).single()

            val edited = editSet(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                draft = ActiveSet(reps = 8, weight = 82_500_000L, rpe = 90, setType = SetType.DROP_SET),
            ).getOrThrow()

            assertEquals(listOf(edited), sessions.getSets(exerciseId).first())
            assertEquals(8, edited.reps)
            assertEquals(82_500_000L, edited.weight)
            assertEquals(90, edited.rpe)
            assertEquals(SetType.DROP_SET, edited.setType)
        }

    @Test
    fun `an edit does not move the set, or change when it was performed`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSets(exerciseId, count = 3)[1]
            clock.advance(600_000L)

            // The draft names an id, a number and a completion of its own. All three are ignored:
            // this is a correction to one row, not a way to renumber the table or to claim the set
            // was performed ten minutes later than it was.
            val edited = editSet(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                draft = ActiveSet(id = 999L, reps = 6, weight = 60_000_000L, isCompleted = false),
            ).getOrThrow()

            assertEquals(logged.id, edited.id)
            assertEquals(logged.setNumber, edited.setNumber)
            assertEquals(logged.completedAt, edited.completedAt)
            assertEquals(logged.createdAt, edited.createdAt)
            assertEquals(true, edited.isCompleted)
            assertEquals("the correction is when the row was touched", 605_000L, edited.updatedAt)
        }

    @Test
    fun `an edit keeps what the editor has no field for`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(
                exerciseId,
                ActiveSet(reps = 5, weight = 60_000_000L, restAfter = 120L, notes = "left elbow"),
            ).getOrThrow()

            // The screen builds its draft from the stored row and lays the four edited fields over
            // it, which is what this asserts is *possible*: the use case writes what it is given, so
            // a caller that built a draft from nothing would silently clear the rest of the set.
            val edited = editSet(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                draft = ActiveSet(
                    reps = 6,
                    weight = logged.weight,
                    restAfter = logged.restAfter,
                    notes = logged.notes,
                ),
            ).getOrThrow()

            assertEquals(120L, edited.restAfter)
            assertEquals("left elbow", edited.notes)
        }

    @Test
    fun `an edit that breaks a bound is refused and writes nothing`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSets(exerciseId, count = 1).single()

            val failure = editSet(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                draft = ActiveSet(reps = 0, weight = 60_000_000L),
            ).exceptionOrNull()

            assertTrue("expected a rejection, was $failure", failure is SessionError.SetRejected)
            assertTrue(
                "the validator's own wording is what a screen shows",
                (failure as SessionError.SetRejected).reason.contains("reps must be within"),
            )
            assertEquals(listOf(logged), sessions.getSets(exerciseId).first())
        }

    @Test
    fun `an edit that empties the dimension the exercise is logged in is refused`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSets(exerciseId, count = 1).single()

            val failure = editSet(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                draft = ActiveSet(reps = null, weight = 60_000_000L),
                exerciseType = ExerciseType.STRENGTH,
            ).exceptionOrNull()

            assertTrue("expected a rejection, was $failure", failure is SessionError.SetRejected)
            assertEquals(listOf(logged), sessions.getSets(exerciseId).first())
        }

    @Test
    fun `a set of another exercise cannot be corrected through this one`() =
        runTest {
            val exerciseId = startWithExercise()
            val other = sessions.addExercise(ACTIVE_SESSION_ID_UNUSED_SESSION)
            val logged = logSets(exerciseId, count = 1).single()

            val failure = editSet(
                sessionExerciseId = other,
                setId = logged.id,
                draft = ActiveSet(reps = 8, weight = 60_000_000L),
            ).exceptionOrNull()

            // Refused as an exercise that is not in the session, which is the outer of the two
            // checks: the row it names is real, and it belongs to a workout nobody is looking at.
            assertTrue("expected a refusal, was $failure", failure is SessionError.ExerciseNotInSession)
            assertEquals(listOf(logged), sessions.getSets(exerciseId).first())
        }

    @Test
    fun `a set id that is not this exercise's is refused`() =
        runTest {
            val exerciseId = startWithExercise()
            logSets(exerciseId, count = 1)

            val failure = editSet(
                sessionExerciseId = exerciseId,
                setId = 4_242L,
                draft = ActiveSet(reps = 8, weight = 60_000_000L),
            ).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.SetNotInExercise)
        }

    @Test
    fun `a paused session takes no corrections`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSets(exerciseId, count = 1).single()
            pauseSession()

            val failure = editSet(
                sessionExerciseId = exerciseId,
                setId = logged.id,
                draft = ActiveSet(reps = 8, weight = 60_000_000L),
            ).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.SessionNotActive)
            assertEquals(listOf(logged), sessions.getSets(exerciseId).first())
        }

    // ─── Deleting ───────────────────────────────────────────────────────────────────────

    @Test
    fun `deleting takes the set out of the table`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)

            deleteSet(exerciseId, sets[1].id).getOrThrow()

            assertEquals(
                listOf(sets[0].id, sets[2].id),
                sessions.getSets(exerciseId).first().sortedBy(WorkoutSet::setNumber).map(WorkoutSet::id),
            )
        }

    @Test
    fun `deleting the middle of a table closes the numbers up`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 4)

            deleteSet(exerciseId, sets[1].id).getOrThrow()

            assertEquals(listOf(1, 2, 3), numbersOf(exerciseId))
            assertEquals(
                "the survivors keep their identity, only their numbers move",
                listOf(sets[0].id, sets[2].id, sets[3].id),
                sessions.getSets(exerciseId).first().sortedBy(WorkoutSet::setNumber).map(WorkoutSet::id),
            )
        }

    @Test
    fun `deleting the last set writes nothing but the deletion`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            sessions.setUpdates.clear()

            deleteSet(exerciseId, sets[2].id).getOrThrow()

            assertEquals(listOf(1, 2), numbersOf(exerciseId))
            assertEquals(
                "nothing moved, so nothing is restamped",
                emptyList<WorkoutSet>(),
                sessions.setUpdates,
            )
        }

    @Test
    fun `the renumbering stamps only the rows that moved`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            clock.advance(60_000L)
            sessions.setUpdates.clear()

            deleteSet(exerciseId, sets[0].id).getOrThrow()

            assertEquals(listOf(sets[1].id, sets[2].id), sessions.setUpdates.map(WorkoutSet::id))
            assertTrue(
                "a row that moved is a row that was touched",
                sessions.setUpdates.all { it.updatedAt == 65_000L },
            )
        }

    @Test
    fun `deleting hands back the set as it stood, with the number it had`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)

            val deleted = deleteSet(exerciseId, sets[1].id).getOrThrow()

            assertEquals(sets[1], deleted)
            assertEquals("the number is what makes the deletion reversible", 2, deleted.setNumber)
        }

    @Test
    fun `a set id that is not this exercise's cannot be deleted through it`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 2)

            val failure = deleteSet(exerciseId, 4_242L).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.SetNotInExercise)
            assertEquals(sets.map(WorkoutSet::id), sessions.getSets(exerciseId).first().map(WorkoutSet::id))
        }

    @Test
    fun `a paused session takes no deletions`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 2)
            pauseSession()

            val failure = deleteSet(exerciseId, sets[0].id).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.SessionNotActive)
            assertEquals(2, sessions.getSets(exerciseId).first().size)
        }

    // ─── Undoing ────────────────────────────────────────────────────────────────────────

    @Test
    fun `undoing a deletion puts the set back where it was`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 4)

            val deleted = deleteSet(exerciseId, sets[1].id).getOrThrow()
            restoreSet(exerciseId, deleted).getOrThrow()

            val table = sessions.getSets(exerciseId).first().sortedBy(WorkoutSet::setNumber)
            assertEquals(listOf(1, 2, 3, 4), table.map(WorkoutSet::setNumber))
            assertEquals(
                "the sets it was between are back where they were",
                listOf(sets[0].id, sets[2].id, sets[3].id),
                listOf(table[0].id, table[2].id, table[3].id),
            )
            assertEquals("and it is back between them", deleted.reps, table[1].reps)
            assertEquals(deleted.weight, table[1].weight)
        }

    @Test
    fun `a restored set keeps every value it was logged with`() =
        runTest {
            val exerciseId = startWithExercise()
            val logged = logSet(
                exerciseId,
                ActiveSet(
                    reps = 5,
                    weight = 80_000_000L,
                    rpe = 95,
                    setType = SetType.FAILURE,
                    notes = "belt on",
                ),
            ).getOrThrow()

            val deleted = deleteSet(exerciseId, logged.id).getOrThrow()
            val restored = restoreSet(exerciseId, deleted).getOrThrow()

            assertEquals(logged.reps, restored.reps)
            assertEquals(logged.weight, restored.weight)
            assertEquals(logged.rpe, restored.rpe)
            assertEquals(logged.setType, restored.setType)
            assertEquals(logged.notes, restored.notes)
            assertEquals("performed when it was performed", logged.completedAt, restored.completedAt)
            assertEquals(1, restored.setNumber)
            assertTrue("it is a new row", restored.id != logged.id)
        }

    @Test
    fun `undoing the deletion of the last set moves nothing`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            val deleted = deleteSet(exerciseId, sets[2].id).getOrThrow()
            sessions.setUpdates.clear()

            restoreSet(exerciseId, deleted).getOrThrow()

            assertEquals(listOf(1, 2, 3), numbersOf(exerciseId))
            assertEquals(
                "the set goes back on the end, which is where it already lands",
                emptyList<WorkoutSet>(),
                sessions.setUpdates,
            )
        }

    @Test
    fun `a set put back after more were logged still goes back to its own place`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 2)
            val deleted = deleteSet(exerciseId, sets[0].id).getOrThrow()

            // The user kept lifting while the offer to undo was up. The restored set was still
            // performed before all of them, so it goes back to the front and the rest move down —
            // an undo puts the table back the way it was, plus whatever happened since.
            logSets(exerciseId, count = 2)
            val restored = restoreSet(exerciseId, deleted).getOrThrow()

            assertEquals(listOf(1, 2, 3, 4), numbersOf(exerciseId))
            assertEquals(1, restored.setNumber)
            assertEquals(
                deleted.reps,
                sessions.getSets(exerciseId).first().minByOrNull(WorkoutSet::setNumber)?.reps,
            )
        }

    @Test
    fun `a set whose number is past the end of the table goes on the end`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 4)
            val deleted = deleteSet(exerciseId, sets[3].id).getOrThrow()

            // Two more deletions, so the number the restored set is asking for — 4 — is past the end
            // of a table that now has one row in it. There is nowhere else for it to go, and
            // refusing the undo over an arithmetic detail would lose a set to defend a number.
            deleteSet(exerciseId, sets[0].id).getOrThrow()
            deleteSet(exerciseId, sets[1].id).getOrThrow()
            val restored = restoreSet(exerciseId, deleted).getOrThrow()

            assertEquals(listOf(1, 2), numbersOf(exerciseId))
            assertEquals(2, restored.setNumber)
        }

    @Test
    fun `a set logged after a deletion does not collide with the survivors`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            deleteSet(exerciseId, sets[1].id).getOrThrow()

            val next = logSet(exerciseId, ActiveSet(reps = 5, weight = 60_000_000L)).getOrThrow()

            assertEquals(3, next.setNumber)
            assertEquals(listOf(1, 2, 3), numbersOf(exerciseId))
        }

    @Test
    fun `an undo offered over a session that is no longer running is refused`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 2)
            val deleted = deleteSet(exerciseId, sets[0].id).getOrThrow()
            state.update(SessionState.Idle)

            val failure = restoreSet(exerciseId, deleted).exceptionOrNull()

            assertTrue("expected a refusal, was $failure", failure is SessionError.SessionNotActive)
            assertEquals(1, sessions.getSets(exerciseId).first().size)
        }

    @Test
    fun `a store that cannot renumber reports the deletion as half done`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            sessions.setUpdateFailure = IllegalStateException("disk full")

            val failure = deleteSet(exerciseId, sets[0].id).exceptionOrNull()

            // Reported rather than swallowed: a table with a gap in it is the state the renumbering
            // exists to prevent, so it is not a detail the caller can be left unaware of. Reported
            // as its own refusal rather than as a failed deletion, because the set has in fact gone
            // — and the row it carries is the only copy of it there is left to offer back.
            assertTrue("expected a refusal, was $failure", failure is SessionError.SetTableNotRenumbered)
            val partial = failure as SessionError.SetTableNotRenumbered
            assertEquals(sets[0].id, partial.set.id)
            assertEquals("the number it had is what puts it back", 1, partial.set.setNumber)
            assertEquals("disk full", partial.cause?.message)
            assertEquals(listOf(2, 3), numbersOf(exerciseId))
        }

    @Test
    fun `a delete the store refuses leaves the set and its numbers alone`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            sessions.setDeleteFailure = IllegalStateException("disk full")

            val failure = deleteSet(exerciseId, sets[0].id).exceptionOrNull()

            // The other half of the pair above: nothing was written, so this is *not* a
            // `SetTableNotRenumbered` — a caller that offered the set back here would restore a set
            // that never went, and end up with it twice.
            assertFalse(
                "nothing was written, so nothing is half done",
                failure is SessionError.SetTableNotRenumbered,
            )
            assertEquals("disk full", failure?.message)
            assertEquals(listOf(1, 2, 3), numbersOf(exerciseId))
        }

    @Test
    fun `a store that cannot renumber reports the restore as half done`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            val deleted = deleteSet(exerciseId, sets[0].id).getOrThrow()
            sessions.setUpdateFailure = IllegalStateException("disk full")

            val failure = restoreSet(exerciseId, deleted).exceptionOrNull()

            // The set is back — at the end of the table, which is what inserting before renumbering
            // buys — so the refusal has to say so rather than read as a restore that did not happen.
            // A caller told nothing was written would have the user log the set a second time.
            assertTrue("expected a refusal, was $failure", failure is SessionError.SetTableNotRenumbered)
            val partial = failure as SessionError.SetTableNotRenumbered
            assertEquals(3, partial.set.setNumber)
            assertEquals(deleted.reps, partial.set.reps)
            assertEquals("disk full", partial.cause?.message)
            assertEquals(3, sessions.getSets(exerciseId).first().size)
        }

    @Test
    fun `a restore the store refuses writes nothing at all`() =
        runTest {
            val exerciseId = startWithExercise()
            val sets = logSets(exerciseId, count = 3)
            val deleted = deleteSet(exerciseId, sets[0].id).getOrThrow()
            sessions.setInsertFailure = IllegalStateException("disk full")

            val failure = restoreSet(exerciseId, deleted).exceptionOrNull()

            assertFalse(
                "nothing was written, so nothing is half done",
                failure is SessionError.SetTableNotRenumbered,
            )
            assertEquals(2, sessions.getSets(exerciseId).first().size)
        }

    private companion object {
        /**
         * A session id nothing started, used to hang an exercise off a workout that is not the
         * running one. `FakeSessionRepository` numbers sessions from 1, so this cannot be one.
         */
        const val ACTIVE_SESSION_ID_UNUSED_SESSION = 99L
    }
}
