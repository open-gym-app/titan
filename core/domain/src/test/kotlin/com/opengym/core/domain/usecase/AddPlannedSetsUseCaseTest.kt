package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionPlan
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.WorkoutSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Laying a plan into an exercise's existing table: where it lands, what moves to make room, and
 * what stays put.
 */
class AddPlannedSetsUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val lock = SessionLifecycleLock()
    private val startPlannedSession = StartPlannedSessionUseCase(
        startSession = StartSessionUseCase(sessions, state, clock, lock),
        sessionRepository = sessions,
        sessionStateRepository = state,
    )
    private val addPlannedSets = AddPlannedSetsUseCase(sessions, state, clock, lock)

    private val rampUp = listOf(
        PlannedSet(setType = SetType.WARMUP, reps = 5, weight = 40_000_000L),
        PlannedSet(setType = SetType.WARMUP, reps = 3, weight = 60_000_000L),
    )

    /** The bench, with [working] sets of 80 kg laid down by a template and none performed. */
    private suspend fun startBench(working: Int = 3): Long {
        val sessionId = startPlannedSession(
            SessionPlan(
                exercises = listOf(
                    SessionExerciseDraft(
                        exerciseId = BENCH,
                        sets = List(working) { PlannedSet(reps = 5, weight = 80_000_000L) },
                    ),
                ),
            ),
        ).getOrThrow()
        return sessions.getExercises(sessionId).first().single().id
    }

    private suspend fun tick(
        bench: Long,
        set: WorkoutSet,
    ) = MarkSetCompletedUseCase(sessions, state, clock)(bench, set.id, isCompleted = true).getOrThrow()

    private suspend fun table(sessionExerciseId: Long): List<WorkoutSet> =
        sessions.getSets(sessionExerciseId).first().sortedBy(WorkoutSet::setNumber)

    @Test
    fun `a plan goes in front of the working sets a template laid down`() =
        runTest {
            val bench = startBench()

            addPlannedSets(bench, rampUp).getOrThrow()

            val table = table(bench)
            assertEquals(listOf(1, 2, 3, 4, 5), table.map(WorkoutSet::setNumber))
            assertEquals(
                listOf(40_000_000L, 60_000_000L, 80_000_000L, 80_000_000L, 80_000_000L),
                table.map(WorkoutSet::weight),
            )
            assertEquals(
                listOf(SetType.WARMUP, SetType.WARMUP, SetType.NORMAL, SetType.NORMAL, SetType.NORMAL),
                table.map(WorkoutSet::setType),
            )
        }

    @Test
    fun `the rows written are unperformed`() =
        runTest {
            val bench = startBench()

            val written = addPlannedSets(bench, rampUp).getOrThrow()

            val rows = table(bench).filter { it.id in written }
            assertEquals(2, rows.size)
            assertTrue(rows.none(WorkoutSet::isCompleted))
            assertEquals(listOf(PlannedSets.UNPERFORMED, PlannedSets.UNPERFORMED), rows.map(WorkoutSet::completedAt))
            assertEquals(listOf(5_000L, 5_000L), rows.map(WorkoutSet::createdAt))
        }

    @Test
    fun `a plan goes after whatever the lifter has already ticked`() =
        runTest {
            val bench = startBench()
            tick(bench, table(bench).first())

            addPlannedSets(bench, rampUp).getOrThrow()

            val table = table(bench)
            // The ticked set keeps its number — a set somebody has performed is not moved out
            // from under them — and the plan goes between it and the two still to come.
            assertEquals(listOf(1, 2, 3, 4, 5), table.map(WorkoutSet::setNumber))
            assertEquals(
                listOf(SetType.NORMAL, SetType.WARMUP, SetType.WARMUP, SetType.NORMAL, SetType.NORMAL),
                table.map(WorkoutSet::setType),
            )
            assertEquals(listOf(true, false, false, false, false), table.map(WorkoutSet::isCompleted))
        }

    @Test
    fun `an exercise with nothing under it is numbered from 1`() =
        runTest {
            val bench = startBench(working = 0)

            addPlannedSets(bench, rampUp).getOrThrow()

            assertEquals(listOf(1, 2), table(bench).map(WorkoutSet::setNumber))
        }

    @Test
    fun `only the rows that moved are rewritten, and they are stamped`() =
        runTest {
            val bench = startBench()
            tick(bench, table(bench).first())
            sessions.setUpdates.clear()
            clock.now = 9_000L

            addPlannedSets(bench, rampUp).getOrThrow()

            // Sets 2 and 3 moved to 4 and 5; set 1 was performed and stays where it is.
            assertEquals(listOf(4, 5), sessions.setUpdates.map(WorkoutSet::setNumber))
            assertEquals(listOf(9_000L, 9_000L), sessions.setUpdates.map(WorkoutSet::updatedAt))
        }

    @Test
    fun `an empty plan writes nothing and is not an error`() =
        runTest {
            val bench = startBench()

            val written = addPlannedSets(bench, emptyList()).getOrThrow()

            assertTrue(written.isEmpty())
            assertEquals(3, table(bench).size)
        }

    @Test
    fun `with no session running it is refused`() =
        runTest {
            val refusal = addPlannedSets(sessionExerciseId = 1L, sets = rampUp).exceptionOrNull()

            assertTrue(refusal is SessionError.SessionNotActive)
        }

    @Test
    fun `an exercise of another session is refused`() =
        runTest {
            val bench = startBench()

            val refusal = addPlannedSets(sessionExerciseId = bench + 100, sets = rampUp).exceptionOrNull()

            assertTrue(refusal is SessionError.ExerciseNotInSession)
            assertEquals(3, table(bench).size)
        }

    @Test
    fun `a store that cannot be written fails rather than throwing`() =
        runTest {
            val bench = startBench()
            val failure = IllegalStateException("disk full")
            sessions.setInsertFailure = failure

            assertEquals(failure, addPlannedSets(bench, rampUp).exceptionOrNull())
        }

    private companion object {
        const val BENCH = 1L
    }
}
