package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionPlan
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing a running workout's layout: adding exercises to it and cutting them out.
 *
 * The two use cases are tested together because they are two halves of one question — what
 * happens to `session_exercise` while a session is live — and because the interesting cases are
 * the ones where they meet: adding after a removal has left a gap in the positions, and removing
 * a member of a superset that another add went on the end of.
 */
class SessionExerciseEditingTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val lock = SessionLifecycleLock()
    private val startPlannedSession = StartPlannedSessionUseCase(
        startSession = StartSessionUseCase(sessions, state, clock, lock),
        sessionRepository = sessions,
        sessionStateRepository = state,
    )
    private val addExercises = AddSessionExercisesUseCase(sessions, state, clock)
    private val removeExercise = RemoveSessionExerciseUseCase(sessions, state)

    /** A push day: bench, then incline and fly performed back to back as a superset. */
    private suspend fun startPushDay(): Long = startPlannedSession(
        SessionPlan(
            exercises = listOf(
                SessionExerciseDraft(exerciseId = BENCH),
                SessionExerciseDraft(exerciseId = INCLINE, supersetGroup = 0),
                SessionExerciseDraft(exerciseId = FLY, supersetGroup = 0),
            ),
        ),
    ).getOrThrow()

    private suspend fun layout(sessionId: Long): List<SessionExercise> =
        sessions.getExercises(sessionId).first().sortedBy(SessionExercise::position)

    // ─── Adding ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `an exercise added mid-session goes on the end`() = runTest {
        val sessionId = startPushDay()

        addExercises(listOf(PUSHDOWN)).getOrThrow()

        assertEquals(
            listOf(BENCH, INCLINE, FLY, PUSHDOWN),
            layout(sessionId).map(SessionExercise::exerciseId),
        )
    }

    @Test
    fun `several are added in the order they were picked`() = runTest {
        val sessionId = startPushDay()

        addExercises(listOf(PUSHDOWN, LATERAL_RAISE)).getOrThrow()

        assertEquals(
            listOf(BENCH, INCLINE, FLY, PUSHDOWN, LATERAL_RAISE),
            layout(sessionId).map(SessionExercise::exerciseId),
        )
    }

    @Test
    fun `adding reports the rows it wrote, in the same order`() = runTest {
        val sessionId = startPushDay()

        val added = addExercises(listOf(PUSHDOWN, LATERAL_RAISE)).getOrThrow()

        val tail = layout(sessionId).takeLast(2)
        assertEquals(tail.map(SessionExercise::id), added)
    }

    @Test
    fun `an added exercise joins no superset`() = runTest {
        // There is no way to say which exercise a new one pairs with from "add to this
        // workout", and `Supersets` requires a group's members to be adjacent anyway.
        val sessionId = startPushDay()

        addExercises(listOf(PUSHDOWN)).getOrThrow()

        assertEquals(null, layout(sessionId).last().supersetGroup)
    }

    @Test
    fun `an added exercise is stamped with when it was added, not when the session began`() =
        runTest {
            val sessionId = startPushDay()
            clock.advance(20 * 60_000L)

            addExercises(listOf(PUSHDOWN)).getOrThrow()

            val added = layout(sessionId).last()
            assertEquals(1_205_000L, added.createdAt)
            assertEquals(5_000L, layout(sessionId).first().createdAt)
        }

    @Test
    fun `adding nothing writes nothing and is not an error`() = runTest {
        val sessionId = startPushDay()

        val result = addExercises(emptyList())

        assertEquals(emptyList<Long>(), result.getOrThrow())
        assertEquals(3, layout(sessionId).size)
    }

    @Test
    fun `adding with no session running is refused`() = runTest {
        val result = addExercises(listOf(PUSHDOWN))

        assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
    }

    @Test
    fun `adding to a paused session is refused`() = runTest {
        startPushDay()
        val paused = PauseSessionUseCase(sessions, state, clock, lock)().getOrThrow()

        val result = addExercises(listOf(PUSHDOWN))

        assertEquals(SessionError.SessionNotActive(paused), result.exceptionOrNull())
    }

    @Test
    fun `adding to a finished session is refused`() = runTest {
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

        val result = AddSessionExercisesUseCase(
            sessions,
            FakeSessionStateRepository(finished),
            clock,
        )(listOf(PUSHDOWN))

        assertEquals(SessionError.SessionNotActive(finished), result.exceptionOrNull())
    }

    @Test
    fun `a store that cannot be written fails rather than throwing`() = runTest {
        startPushDay()
        val failure = IllegalStateException("disk full")
        sessions.exerciseInsertFailure = failure

        assertEquals(failure, addExercises(listOf(PUSHDOWN)).exceptionOrNull())
    }

    // ─── Removing ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an exercise with nothing logged against it can be cut`() = runTest {
        val sessionId = startPushDay()
        val fly = layout(sessionId).last()

        assertEquals(fly.id, removeExercise(fly.id).getOrThrow())
        assertEquals(listOf(BENCH, INCLINE), layout(sessionId).map(SessionExercise::exerciseId))
    }

    @Test
    fun `cutting the exercise a superset was paired with dissolves the group`() = runTest {
        // A group of one is not a superset — see `Supersets` — so the survivor is repaired
        // rather than left drawn as `SS A1` with no `A2` under it.
        val sessionId = startPushDay()
        val fly = layout(sessionId).last()

        removeExercise(fly.id).getOrThrow()

        assertEquals(listOf(null, null), layout(sessionId).map(SessionExercise::supersetGroup))
    }

    @Test
    fun `a removal that changes no grouping rewrites no rows`() = runTest {
        val sessionId = startPushDay()
        val bench = layout(sessionId).first()

        removeExercise(bench.id).getOrThrow()

        // Incline and fly are still a pair, so nothing about them changed and neither was
        // written — a loop that rewrote the whole layout would re-emit every observer of the
        // session on each removal.
        assertTrue(sessions.exerciseUpdates.isEmpty())
        assertEquals(listOf(0, 0), layout(sessionId).map(SessionExercise::supersetGroup))
    }

    @Test
    fun `the survivors keep their positions, so a removal leaves a gap`() = runTest {
        // Deliberate: nothing reads position as a dense index, and renumbering every surviving
        // row on each removal is a write nobody benefits from.
        val sessionId = startPushDay()
        val incline = layout(sessionId)[1]

        removeExercise(incline.id).getOrThrow()

        assertEquals(listOf(0, 2), layout(sessionId).map(SessionExercise::position))
    }

    @Test
    fun `an exercise added after a removal does not collide with a surviving position`() =
        runTest {
            // The case the `MAX(position) + 1` rule exists for: after a cut the count and the
            // highest position disagree, and numbering from the count would land on a row that
            // is still there.
            val sessionId = startPushDay()
            removeExercise(layout(sessionId)[1].id).getOrThrow()

            addExercises(listOf(PUSHDOWN)).getOrThrow()

            assertEquals(listOf(0, 2, 3), layout(sessionId).map(SessionExercise::position))
        }

    @Test
    fun `an exercise with a logged set is refused rather than silently taking the set with it`() =
        runTest {
            val sessionId = startPushDay()
            val bench = layout(sessionId).first()
            sessions.addSet(bench.id)

            val result = removeExercise(bench.id)

            assertEquals(
                SessionError.ExerciseHasLoggedSets(sessionExerciseId = bench.id, setCount = 1),
                result.exceptionOrNull(),
            )
            assertEquals(3, layout(sessionId).size)
        }

    @Test
    fun `once its sets are gone the exercise goes freely`() = runTest {
        // The refusal is a rule with a way past it, which is what makes it reasonable rather
        // than an obstruction: #99 deletes the set, and the exercise is then empty.
        val sessionId = startPushDay()
        val bench = layout(sessionId).first()
        sessions.addSet(bench.id)
        assertTrue(removeExercise(bench.id).isFailure)

        sessions.removeSet(bench.id, setNumber = 1)

        assertEquals(bench.id, removeExercise(bench.id).getOrThrow())
    }

    @Test
    fun `removing an exercise of another session is refused`() = runTest {
        val sessionId = startPushDay()
        val stale = sessions.addExercise(sessionId = 99L)

        val result = removeExercise(stale)

        assertEquals(
            SessionError.ExerciseNotInSession(sessionExerciseId = stale, sessionId = sessionId),
            result.exceptionOrNull(),
        )
    }

    @Test
    fun `removing with no session running is refused`() = runTest {
        val result = removeExercise(1L)

        assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
    }

    @Test
    fun `removing from a paused session is refused`() = runTest {
        val sessionId = startPushDay()
        val bench = layout(sessionId).first()
        val paused = PauseSessionUseCase(sessions, state, clock, lock)().getOrThrow()

        val result = removeExercise(bench.id)

        assertEquals(SessionError.SessionNotActive(paused), result.exceptionOrNull())
    }

    @Test
    fun `a delete that fails leaves the exercise where it was`() = runTest {
        val sessionId = startPushDay()
        val failure = IllegalStateException("disk full")
        sessions.exerciseDeleteFailure = failure

        val result = removeExercise(layout(sessionId).first().id)

        assertEquals(failure, result.exceptionOrNull())
        assertEquals(3, layout(sessionId).size)
    }

    @Test
    fun `the refusal surfaces as a descriptive IllegalStateException`() = runTest {
        val sessionId = startPushDay()
        val bench = layout(sessionId).first()
        sessions.addSet(bench.id)

        val refused = removeExercise(bench.id)

        val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }

        assertEquals(
            "exercise ${bench.id} has 1 logged set(s) and cannot be removed",
            thrown.message,
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
