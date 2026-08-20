package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionPlan
import com.opengym.core.domain.model.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing a running workout's exercises up, and breaking the pairs again.
 *
 * The two use cases are tested together because a superset is only ever reached through both:
 * the interesting cases are a group grown to three by two links and then split back down the
 * middle, and neither use case can produce those on its own.
 *
 * The rules over the grouping itself are `SupersetsTest`'s. What is asserted here is what the
 * *store* ends up holding, and which rows were written to get there.
 */
class SessionSupersetEditingTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startPlannedSession = StartPlannedSessionUseCase(
        startSession = StartSessionUseCase(sessions, state, clock, SessionLifecycleLock()),
        sessionRepository = sessions,
        sessionStateRepository = state,
    )
    private val link = LinkSupersetUseCase(sessions, state, clock)
    private val unlink = UnlinkSupersetUseCase(sessions, state, clock)

    /** Four exercises, none of them paired with any other. */
    private suspend fun startPushDay(): Long = startPlannedSession(
        SessionPlan(
            exercises = listOf(
                SessionExerciseDraft(exerciseId = BENCH),
                SessionExerciseDraft(exerciseId = INCLINE),
                SessionExerciseDraft(exerciseId = FLY),
                SessionExerciseDraft(exerciseId = PUSHDOWN),
            ),
        ),
    ).getOrThrow()

    private suspend fun layout(sessionId: Long): List<SessionExercise> =
        sessions.getExercises(sessionId).first().sortedBy(SessionExercise::position)

    private suspend fun grouping(sessionId: Long): List<Int?> =
        layout(sessionId).map(SessionExercise::supersetGroup)

    // ─── Linking ────────────────────────────────────────────────────────────────────────

    @Test
    fun `linking an exercise pairs it with the one performed next`() = runTest {
        val sessionId = startPushDay()

        link(layout(sessionId)[1].id).getOrThrow()

        assertEquals(listOf(null, 0, 0, null), grouping(sessionId))
    }

    @Test
    fun `linking again from the group's last member makes it a three`() = runTest {
        // Two taps on two different joins, which is the only way to a three-member superset
        // on a screen whose control is the gap between two exercises.
        val sessionId = startPushDay()

        link(layout(sessionId)[1].id).getOrThrow()
        link(layout(sessionId)[2].id).getOrThrow()

        assertEquals(listOf(null, 0, 0, 0), grouping(sessionId))
    }

    @Test
    fun `linking two groups merges them rather than splitting either`() = runTest {
        val sessionId = startPushDay()
        link(layout(sessionId)[0].id).getOrThrow()
        link(layout(sessionId)[2].id).getOrThrow()

        link(layout(sessionId)[1].id).getOrThrow()

        assertEquals(listOf(0, 0, 0, 0), grouping(sessionId))
    }

    @Test
    fun `the groups are numbered down the workout`() = runTest {
        val sessionId = startPushDay()

        link(layout(sessionId)[2].id).getOrThrow()
        link(layout(sessionId)[0].id).getOrThrow()

        // Formed bottom-up, numbered top-down: the labels a screen reads off this are
        // `SS A` over `SS B` whichever order the user made them in.
        assertEquals(listOf(0, 0, 1, 1), grouping(sessionId))
    }

    @Test
    fun `the last exercise has nothing to link to`() = runTest {
        val sessionId = startPushDay()

        val result = link(layout(sessionId).last().id)

        assertTrue(result.isFailure)
        assertEquals(listOf(null, null, null, null), grouping(sessionId))
    }

    @Test
    fun `linking reports the grouping it wrote`() = runTest {
        val sessionId = startPushDay()

        val written = link(layout(sessionId)[1].id).getOrThrow()

        assertEquals(grouping(sessionId), written)
    }

    @Test
    fun `only the rows whose group changed are written`() = runTest {
        val sessionId = startPushDay()

        link(layout(sessionId)[1].id).getOrThrow()

        // Two rows moved into a group; bench and the pushdown were never touched.
        assertEquals(listOf(INCLINE, FLY), sessions.exerciseUpdates.map(SessionExercise::exerciseId))
    }

    @Test
    fun `a row that moves is stamped with when it moved`() = runTest {
        val sessionId = startPushDay()
        clock.advance(20 * 60_000L)

        link(layout(sessionId)[1].id).getOrThrow()

        assertEquals(1_205_000L, layout(sessionId)[1].updatedAt)
        assertEquals(5_000L, layout(sessionId)[0].updatedAt)
    }

    @Test
    fun `an exercise with sets logged against it can still be paired up`() = runTest {
        // Unlike a removal: a grouping says how the session is paced and destroys nothing.
        val sessionId = startPushDay()
        LogSetUseCase(sessions, state, clock)(
            sessionExerciseId = layout(sessionId)[1].id,
            draft = ActiveSet(reps = 8, weight = 60_000_000L),
        ).getOrThrow()

        link(layout(sessionId)[1].id).getOrThrow()

        assertEquals(listOf(null, 0, 0, null), grouping(sessionId))
    }

    // ─── Unlinking ──────────────────────────────────────────────────────────────────────

    @Test
    fun `unlinking a pair leaves both exercises standing alone`() = runTest {
        val sessionId = startPushDay()
        link(layout(sessionId)[1].id).getOrThrow()

        unlink(layout(sessionId)[1].id).getOrThrow()

        assertEquals(listOf(null, null, null, null), grouping(sessionId))
    }

    @Test
    fun `unlinking inside a three breaks one join, not the group`() = runTest {
        val sessionId = startPushDay()
        link(layout(sessionId)[1].id).getOrThrow()
        link(layout(sessionId)[2].id).getOrThrow()

        unlink(layout(sessionId)[2].id).getOrThrow()

        assertEquals(listOf(null, 0, 0, null), grouping(sessionId))
    }

    @Test
    fun `unlinking where there is no pair is not an error`() = runTest {
        val sessionId = startPushDay()

        unlink(layout(sessionId)[0].id).getOrThrow()

        assertEquals(listOf(null, null, null, null), grouping(sessionId))
        assertTrue(sessions.exerciseUpdates.isEmpty())
    }

    // ─── Refusals ───────────────────────────────────────────────────────────────────────

    @Test
    fun `neither works on a session that is not running`() = runTest {
        val sessionId = startPushDay()
        val incline = layout(sessionId)[1].id
        state.update(SessionState.Idle)

        assertTrue(link(incline).exceptionOrNull() is SessionError.SessionNotActive)
        assertTrue(unlink(incline).exceptionOrNull() is SessionError.SessionNotActive)
    }

    @Test
    fun `neither works on an exercise from another session`() = runTest {
        startPushDay()

        val failure = link(9_999L).exceptionOrNull()

        assertTrue(failure is SessionError.ExerciseNotInSession)
    }

    private companion object {
        const val BENCH = 1L
        const val INCLINE = 2L
        const val FLY = 3L
        const val PUSHDOWN = 4L
    }
}
