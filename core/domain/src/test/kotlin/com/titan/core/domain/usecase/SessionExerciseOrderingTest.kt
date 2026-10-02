package com.titan.core.domain.usecase

import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SessionPlan
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.WorkoutSet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Changing the order of a running workout, and changing what is in one of its slots.
 *
 * The two use cases are tested together because they are the same subject from two sides — a
 * plan meeting a gym that will not cooperate — and because the case that matters most is one
 * neither can produce alone: what happens to a superset when the exercises around it move.
 *
 * The rules over a grouping itself are `SupersetsTest`'s. What is asserted here is what the
 * *store* ends up holding, and which rows were written to get there.
 */
class SessionExerciseOrderingTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val lock = SessionLifecycleLock()
    private val startPlannedSession = StartPlannedSessionUseCase(
        startSession = StartSessionUseCase(sessions, state, clock, lock),
        sessionRepository = sessions,
        sessionStateRepository = state,
    )
    private val reorder = ReorderSessionExercisesUseCase(sessions, state, clock)
    private val swap = SwapSessionExerciseUseCase(sessions, state, clock)
    private val removeExercise = RemoveSessionExerciseUseCase(sessions, state)
    private val logSet = LogSetUseCase(sessions, state, clock)

    /** Four exercises, none of them paired with any other. */
    private suspend fun startPushDay(): Long =
        startPlannedSession(
            SessionPlan(
                exercises = listOf(
                    SessionExerciseDraft(exerciseId = BENCH),
                    SessionExerciseDraft(exerciseId = INCLINE),
                    SessionExerciseDraft(exerciseId = FLY),
                    SessionExerciseDraft(exerciseId = PUSHDOWN),
                ),
            ),
        ).getOrThrow()

    /** The same four, with the fly and the pushdown run back to back. */
    private suspend fun startPushDayWithSuperset(): Long =
        startPlannedSession(
            SessionPlan(
                exercises = listOf(
                    SessionExerciseDraft(exerciseId = BENCH),
                    SessionExerciseDraft(exerciseId = INCLINE),
                    SessionExerciseDraft(exerciseId = FLY, supersetGroup = 0),
                    SessionExerciseDraft(exerciseId = PUSHDOWN, supersetGroup = 0),
                ),
            ),
        ).getOrThrow()

    private suspend fun layout(sessionId: Long): List<SessionExercise> =
        sessions.getExercises(sessionId).first().sortedBy(SessionExercise::position)

    /** The workout as the exercises it is made of, in the order it is performed. */
    private suspend fun order(sessionId: Long): List<Long> = layout(sessionId).map(SessionExercise::exerciseId)

    private suspend fun grouping(sessionId: Long): List<Int?> = layout(sessionId).map(SessionExercise::supersetGroup)

    // ─── Reordering ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an exercise moved up is performed there instead`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId)

            reorder(listOf(rows[2].id, rows[0].id, rows[1].id, rows[3].id)).getOrThrow()

            assertEquals(listOf(FLY, BENCH, INCLINE, PUSHDOWN), order(sessionId))
        }

    @Test
    fun `the positions come out dense, from zero`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId)

            reorder(listOf(rows[3].id, rows[2].id, rows[1].id, rows[0].id)).getOrThrow()

            assertEquals(listOf(0, 1, 2, 3), layout(sessionId).map(SessionExercise::position))
        }

    @Test
    fun `a reorder closes the gap a removal left`() =
        runTest {
            // Removals leave positions alone by design — `RemoveSessionExerciseUseCase` — so the
            // first thing a reorder has to survive is a layout numbered 0, 2, 3.
            val sessionId = startPushDay()
            removeExercise(layout(sessionId)[1].id).getOrThrow()
            val rows = layout(sessionId)

            reorder(rows.map(SessionExercise::id).reversed()).getOrThrow()

            assertEquals(listOf(0, 1, 2), layout(sessionId).map(SessionExercise::position))
            assertEquals(listOf(PUSHDOWN, FLY, BENCH), order(sessionId))
        }

    @Test
    fun `a superset moved with both its members stays one`() =
        runTest {
            val sessionId = startPushDayWithSuperset()
            val rows = layout(sessionId)

            // Both members to the top, still next to each other: nothing has been pulled apart.
            reorder(listOf(rows[2].id, rows[3].id, rows[0].id, rows[1].id)).getOrThrow()

            assertEquals(listOf(FLY, PUSHDOWN, BENCH, INCLINE), order(sessionId))
            assertEquals(listOf(0, 0, null, null), grouping(sessionId))
        }

    @Test
    fun `an exercise dragged out of a pair leaves it, and the pair dissolves`() =
        runTest {
            val sessionId = startPushDayWithSuperset()
            val rows = layout(sessionId)

            // The fly to the top, away from the pushdown it was paired with.
            reorder(listOf(rows[2].id, rows[0].id, rows[1].id, rows[3].id)).getOrThrow()

            assertEquals(listOf(FLY, BENCH, INCLINE, PUSHDOWN), order(sessionId))
            // One exercise left in a group is not a superset — see `Supersets` — so what is left
            // is two exercises that are simply performed one after the other.
            assertEquals(listOf(null, null, null, null), grouping(sessionId))
        }

    @Test
    fun `pulling the middle out of a three closes it up`() =
        runTest {
            val sessionId = startPlannedSession(
                SessionPlan(
                    exercises = listOf(
                        SessionExerciseDraft(exerciseId = BENCH),
                        SessionExerciseDraft(exerciseId = INCLINE, supersetGroup = 0),
                        SessionExerciseDraft(exerciseId = FLY, supersetGroup = 0),
                        SessionExerciseDraft(exerciseId = PUSHDOWN, supersetGroup = 0),
                    ),
                ),
            ).getOrThrow()
            val rows = layout(sessionId)

            // The fly to the top; the incline and the pushdown are neighbours now.
            reorder(listOf(rows[2].id, rows[0].id, rows[1].id, rows[3].id)).getOrThrow()

            assertEquals(listOf(FLY, BENCH, INCLINE, PUSHDOWN), order(sessionId))
            assertEquals(listOf(null, null, 0, 0), grouping(sessionId))
        }

    @Test
    fun `an exercise dropped into a superset cuts it in two`() =
        runTest {
            val sessionId = startPushDayWithSuperset()
            val rows = layout(sessionId)

            // The bench between the fly and the pushdown, which is the other way a drag can
            // separate two members.
            reorder(listOf(rows[1].id, rows[2].id, rows[0].id, rows[3].id)).getOrThrow()

            assertEquals(listOf(INCLINE, FLY, BENCH, PUSHDOWN), order(sessionId))
            assertEquals(listOf(null, null, null, null), grouping(sessionId))
        }

    @Test
    fun `a group cut in two keeps the half that is still a superset`() =
        runTest {
            val sessionId = startPlannedSession(
                SessionPlan(
                    exercises = listOf(
                        SessionExerciseDraft(exerciseId = BENCH),
                        SessionExerciseDraft(exerciseId = INCLINE, supersetGroup = 0),
                        SessionExerciseDraft(exerciseId = FLY, supersetGroup = 0),
                        SessionExerciseDraft(exerciseId = PUSHDOWN, supersetGroup = 0),
                    ),
                ),
            ).getOrThrow()
            val rows = layout(sessionId)

            // The bench dropped between the fly and the pushdown: the incline and the fly are
            // still back to back, the pushdown is on its own.
            reorder(listOf(rows[1].id, rows[2].id, rows[0].id, rows[3].id)).getOrThrow()

            assertEquals(listOf(INCLINE, FLY, BENCH, PUSHDOWN), order(sessionId))
            assertEquals(listOf(0, 0, null, null), grouping(sessionId))
        }

    @Test
    fun `the groups are renumbered down the workout`() =
        runTest {
            val sessionId = startPlannedSession(
                SessionPlan(
                    exercises = listOf(
                        SessionExerciseDraft(exerciseId = BENCH, supersetGroup = 0),
                        SessionExerciseDraft(exerciseId = INCLINE, supersetGroup = 0),
                        SessionExerciseDraft(exerciseId = FLY, supersetGroup = 1),
                        SessionExerciseDraft(exerciseId = PUSHDOWN, supersetGroup = 1),
                    ),
                ),
            ).getOrThrow()
            val rows = layout(sessionId)

            reorder(listOf(rows[2].id, rows[3].id, rows[0].id, rows[1].id)).getOrThrow()

            // The second superset is now the first one performed, so it is the one called `SS A`.
            // A screen reading the stored ordinals must not draw `SS B` above `SS A`.
            assertEquals(listOf(0, 0, 1, 1), grouping(sessionId))
            assertEquals(listOf(FLY, PUSHDOWN, BENCH, INCLINE), order(sessionId))
        }

    @Test
    fun `an order missing an exercise is refused`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId)

            val result = reorder(listOf(rows[1].id, rows[0].id, rows[2].id))

            assertTrue(result.exceptionOrNull() is SessionError.InvalidExerciseOrder)
            assertEquals(listOf(BENCH, INCLINE, FLY, PUSHDOWN), order(sessionId))
        }

    @Test
    fun `an order naming one exercise twice is refused`() =
        runTest {
            // The set of ids matches; the count does not. Only the count catches this one, and
            // letting it through would put two rows on one position.
            val sessionId = startPushDay()
            val rows = layout(sessionId)

            val result = reorder(listOf(rows[0].id, rows[0].id, rows[1].id, rows[2].id, rows[3].id))

            assertTrue(result.exceptionOrNull() is SessionError.InvalidExerciseOrder)
            assertEquals(listOf(0, 1, 2, 3), layout(sessionId).map(SessionExercise::position))
        }

    @Test
    fun `an exercise from another session is refused`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId)

            val result = reorder(listOf(rows[0].id, rows[1].id, rows[2].id, 9_999L))

            assertTrue(result.exceptionOrNull() is SessionError.InvalidExerciseOrder)
        }

    @Test
    fun `only the rows that moved are written`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId)

            reorder(listOf(rows[1].id, rows[0].id, rows[2].id, rows[3].id)).getOrThrow()

            // The two that changed places. The fly and the pushdown are where they were, so their
            // `updated_at` has no business moving.
            assertEquals(listOf(INCLINE, BENCH), sessions.exerciseUpdates.map(SessionExercise::exerciseId))
        }

    @Test
    fun `the order it was already in writes nothing at all`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId)

            val result = reorder(rows.map(SessionExercise::id))

            assertTrue(result.isSuccess)
            assertTrue(sessions.exerciseUpdates.isEmpty())
        }

    @Test
    fun `a row that moves is stamped with when it moved`() =
        runTest {
            val sessionId = startPushDay()
            clock.advance(20 * 60_000L)
            val rows = layout(sessionId)

            reorder(listOf(rows[1].id, rows[0].id, rows[2].id, rows[3].id)).getOrThrow()

            assertEquals(1_205_000L, layout(sessionId)[0].updatedAt)
            assertEquals(5_000L, layout(sessionId)[2].updatedAt)
        }

    @Test
    fun `reordering reports the order it wrote`() =
        runTest {
            val sessionId = startPushDay()
            val asked = layout(sessionId).map(SessionExercise::id).reversed()

            val written = reorder(asked).getOrThrow()

            assertEquals(asked, written)
            assertEquals(asked, layout(sessionId).map(SessionExercise::id))
        }

    @Test
    fun `an exercise already worked reorders, and keeps every set`() =
        runTest {
            val sessionId = startPushDay()
            val bench = layout(sessionId)[0]
            logSet(bench.id, ActiveSet(reps = 8, weight = 80_000_000L)).getOrThrow()
            logSet(bench.id, ActiveSet(reps = 6, weight = 90_000_000L)).getOrThrow()

            reorder(layout(sessionId).map(SessionExercise::id).reversed()).getOrThrow()

            // Position is the order the workout is *performed* in; when a set happened is its own
            // `completed_at`, and nothing here touches either the sets or their exercise's identity.
            assertEquals(3, layout(sessionId).indexOfFirst { it.id == bench.id })
            assertEquals(
                listOf(80_000_000L, 90_000_000L),
                sessions.getSets(bench.id).first().map(WorkoutSet::weight),
            )
        }

    @Test
    fun `reordering with no session running is refused`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId).map(SessionExercise::id)
            state.update(SessionState.Idle)

            assertEquals(
                SessionError.SessionNotActive(SessionState.Idle),
                reorder(rows.reversed()).exceptionOrNull(),
            )
        }

    @Test
    fun `reordering a paused session is refused`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId).map(SessionExercise::id)
            val paused = PauseSessionUseCase(sessions, state, clock, lock)().getOrThrow()

            assertEquals(
                SessionError.SessionNotActive(paused),
                reorder(rows.reversed()).exceptionOrNull(),
            )
        }

    // ─── Swapping ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a swapped slot holds the new exercise and keeps its place`() =
        runTest {
            val sessionId = startPushDay()
            val incline = layout(sessionId)[1]

            val swapped = swap(incline.id, LATERAL_RAISE).getOrThrow()

            assertEquals(LATERAL_RAISE, swapped.exerciseId)
            assertEquals(listOf(BENCH, LATERAL_RAISE, FLY, PUSHDOWN), order(sessionId))
            assertEquals(1, layout(sessionId)[1].position)
            assertEquals(incline.id, layout(sessionId)[1].id)
        }

    @Test
    fun `a swapped exercise stays in the superset the slot was in`() =
        runTest {
            val sessionId = startPushDayWithSuperset()
            val fly = layout(sessionId)[2]

            swap(fly.id, LATERAL_RAISE).getOrThrow()

            assertEquals(listOf(null, null, 0, 0), grouping(sessionId))
            assertEquals(listOf(BENCH, INCLINE, LATERAL_RAISE, PUSHDOWN), order(sessionId))
        }

    @Test
    fun `the sets logged against every other exercise survive a swap`() =
        runTest {
            val sessionId = startPushDay()
            val rows = layout(sessionId)
            logSet(rows[0].id, ActiveSet(reps = 8, weight = 80_000_000L)).getOrThrow()
            logSet(rows[2].id, ActiveSet(reps = 12, weight = 20_000_000L)).getOrThrow()

            swap(rows[1].id, LATERAL_RAISE).getOrThrow()

            assertEquals(1, sessions.getSets(rows[0].id).first().size)
            assertEquals(1, sessions.getSets(rows[2].id).first().size)
            assertEquals(2, sessions.getSetsForSession(sessionId).first().size)
        }

    @Test
    fun `a slot with sets logged against it is refused rather than reattributed`() =
        runTest {
            // The sets would stay — nothing cascades here — attached to a movement they were not
            // performed on, at weights nobody lifted on it.
            val sessionId = startPushDay()
            val bench = layout(sessionId)[0]
            logSet(bench.id, ActiveSet(reps = 8, weight = 80_000_000L)).getOrThrow()

            val result = swap(bench.id, LATERAL_RAISE)

            assertEquals(
                SessionError.ExerciseHasLoggedSets(bench.id, 1),
                result.exceptionOrNull(),
            )
            assertEquals(listOf(BENCH, INCLINE, FLY, PUSHDOWN), order(sessionId))
        }

    @Test
    fun `once its sets are gone the slot swaps freely`() =
        runTest {
            val sessionId = startPushDay()
            val bench = layout(sessionId)[0]
            logSet(bench.id, ActiveSet(reps = 8, weight = 80_000_000L)).getOrThrow()
            sessions.removeSet(bench.id, setNumber = 1)

            swap(bench.id, LATERAL_RAISE).getOrThrow()

            assertEquals(LATERAL_RAISE, layout(sessionId)[0].exerciseId)
        }

    @Test
    fun `swapping an exercise for itself writes nothing`() =
        runTest {
            val sessionId = startPushDay()
            val bench = layout(sessionId)[0]

            val swapped = swap(bench.id, BENCH).getOrThrow()

            assertEquals(bench, swapped)
            assertTrue(sessions.exerciseUpdates.isEmpty())
        }

    @Test
    fun `a swapped slot no longer claims to come from a template`() =
        runTest {
            val sessionId = startPlannedSession(
                SessionPlan(
                    exercises = listOf(
                        SessionExerciseDraft(exerciseId = BENCH, templateExerciseId = 42L),
                    ),
                ),
            ).getOrThrow()

            swap(layout(sessionId)[0].id, LATERAL_RAISE).getOrThrow()

            // Provenance, not a link anything reads back: after a swap it would say this row came
            // from a template exercise it has nothing to do with.
            assertNull(layout(sessionId)[0].templateExerciseId)
        }

    @Test
    fun `a swapped row is stamped with when it changed`() =
        runTest {
            val sessionId = startPushDay()
            clock.advance(20 * 60_000L)

            swap(layout(sessionId)[0].id, LATERAL_RAISE).getOrThrow()

            assertEquals(1_205_000L, layout(sessionId)[0].updatedAt)
            assertEquals(5_000L, layout(sessionId)[1].updatedAt)
        }

    @Test
    fun `swapping an exercise of another session is refused`() =
        runTest {
            startPushDay()

            val result = swap(9_999L, LATERAL_RAISE)

            assertTrue(result.exceptionOrNull() is SessionError.ExerciseNotInSession)
        }

    @Test
    fun `swapping with no session running is refused`() =
        runTest {
            val sessionId = startPushDay()
            val bench = layout(sessionId)[0].id
            state.update(SessionState.Idle)

            assertEquals(
                SessionError.SessionNotActive(SessionState.Idle),
                swap(bench, LATERAL_RAISE).exceptionOrNull(),
            )
        }

    @Test
    fun `swapping in a paused session is refused`() =
        runTest {
            val sessionId = startPushDay()
            val bench = layout(sessionId)[0].id
            val paused = PauseSessionUseCase(sessions, state, clock, lock)().getOrThrow()

            assertEquals(
                SessionError.SessionNotActive(paused),
                swap(bench, LATERAL_RAISE).exceptionOrNull(),
            )
        }

    private companion object {
        const val BENCH = 1L
        const val INCLINE = 2L
        const val FLY = 3L
        const val PUSHDOWN = 4L
        const val LATERAL_RAISE = 5L
    }
}
