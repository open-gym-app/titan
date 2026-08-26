package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionPlan
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.toSessionPlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StartPlannedSessionUseCaseTest {

    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val templates = FakeTemplateRepository()
    private val clock = MutableClock(now = 5_000L)

    private val startPlannedSession = StartPlannedSessionUseCase(
        startSession = StartSessionUseCase(sessions, state, clock, SessionLifecycleLock()),
        sessionRepository = sessions,
        sessionStateRepository = state,
    )

    @Test
    fun `starting lays the exercises out in the order they were planned`() =
        runTest {
            val sessionId = startPlannedSession(SessionPlan.fromExercises(listOf(3L, 1L, 2L))).getOrThrow()

            val laidOut = sessions.storedExercises(sessionId)
            assertEquals(listOf(3L, 1L, 2L), laidOut.map(SessionExercise::exerciseId))
            assertEquals(listOf(0, 1, 2), laidOut.map(SessionExercise::position))
        }

    @Test
    fun `starting moves the state machine to active`() =
        runTest {
            val sessionId = startPlannedSession(SessionPlan.fromExercises(listOf(1L))).getOrThrow()

            assertEquals(SessionState.Active(sessionId = sessionId, startedAt = 5_000L), state.current)
        }

    @Test
    fun `starting stamps the exercises with the session's own start`() =
        runTest {
            val sessionId = startPlannedSession(SessionPlan.fromExercises(listOf(1L, 2L))).getOrThrow()

            val stamps = sessions.storedExercises(sessionId).map(SessionExercise::createdAt)
            assertEquals(listOf(5_000L, 5_000L), stamps)
        }

    @Test
    fun `starting from a template takes its name, its id and its supersets`() =
        runTest {
            val templateId = templates.seed(
                "Push day · Upper",
                listOf(
                    draft(exerciseId = 1L),
                    draft(exerciseId = 2L, supersetGroup = 0),
                    draft(exerciseId = 3L, supersetGroup = 0),
                ),
            )
            val plan = ApplyTemplateUseCase(templates)(templateId).getOrThrow().toSessionPlan()

            val sessionId = startPlannedSession(plan).getOrThrow()

            val session = sessions.stored(sessionId)!!
            assertEquals("Push day · Upper", session.name)
            assertEquals(templateId, session.templateId)
            assertEquals(
                listOf(null, 0, 0),
                sessions.storedExercises(sessionId).map(SessionExercise::supersetGroup),
            )
        }

    @Test
    fun `starting from a template records which template line each exercise came from`() =
        runTest {
            val templateId = templates.seed("Push day", listOf(draft(exerciseId = 1L), draft(exerciseId = 2L)))
            val plan = ApplyTemplateUseCase(templates)(templateId).getOrThrow().toSessionPlan()

            val sessionId = startPlannedSession(plan).getOrThrow()

            assertEquals(
                templates.storedExercises(templateId).map { it.id },
                sessions.storedExercises(sessionId).map(SessionExercise::templateExerciseId),
            )
        }

    @Test
    fun `starting from picked exercises leaves the session unnamed`() =
        runTest {
            val sessionId = startPlannedSession(SessionPlan.fromExercises(listOf(1L))).getOrThrow()

            val session = sessions.stored(sessionId)!!
            assertNull(session.name)
            assertNull(session.templateId)
        }

    @Test
    fun `starting dissolves a superset left with one member`() =
        runTest {
            // What removing one of a pair from the review panel does. The grouping is repaired
            // on the way in rather than asked of the caller, matching the template write path.
            val plan = SessionPlan(
                exercises = listOf(
                    SessionExerciseDraft(exerciseId = 1L, supersetGroup = 7),
                    SessionExerciseDraft(exerciseId = 2L, supersetGroup = 9),
                    SessionExerciseDraft(exerciseId = 3L, supersetGroup = 9),
                ),
            )

            val sessionId = startPlannedSession(plan).getOrThrow()

            // Group 7 dissolved; group 9 survived and was renumbered to the ordinal 0.
            assertEquals(
                listOf(null, 0, 0),
                sessions.storedExercises(sessionId).map(SessionExercise::supersetGroup),
            )
        }

    @Test
    fun `starting a plan with no exercises opens an empty session`() =
        runTest {
            val sessionId = startPlannedSession(SessionPlan(exercises = emptyList())).getOrThrow()

            assertEquals(emptyList<SessionExercise>(), sessions.storedExercises(sessionId))
            assertTrue(sessions.stored(sessionId)!!.isInProgress)
        }

    @Test
    fun `starting while a session is already running is refused and writes nothing`() =
        runTest {
            val running = startPlannedSession(SessionPlan.fromExercises(listOf(1L))).getOrThrow()

            val result = startPlannedSession(SessionPlan.fromExercises(listOf(2L)))

            assertEquals(SessionError.SessionAlreadyActive(running), result.exceptionOrNull())
            assertEquals(SessionState.Active(sessionId = running, startedAt = 5_000L), state.current)
        }

    @Test
    fun `a layout that fails leaves no session row behind`() =
        runTest {
            sessions.exerciseInsertFailure = IOException("disk full")

            val result = startPlannedSession(SessionPlan.fromExercises(listOf(1L)))

            assertTrue(result.isFailure)
            // The row the start had already written is gone, so the next attempt is not
            // refused by a session nobody can see.
            assertNull(sessions.stored(1L))
        }

    @Test
    fun `a layout that fails puts the state machine back to idle`() =
        runTest {
            sessions.exerciseInsertFailure = IOException("disk full")

            startPlannedSession(SessionPlan.fromExercises(listOf(1L)))

            assertEquals(SessionState.Idle, state.current)
        }

    @Test
    fun `a session can be started again after a layout failed`() =
        runTest {
            sessions.exerciseInsertFailure = IOException("disk full")
            startPlannedSession(SessionPlan.fromExercises(listOf(1L)))
            sessions.exerciseInsertFailure = null

            val sessionId = startPlannedSession(SessionPlan.fromExercises(listOf(1L))).getOrThrow()

            assertEquals(SessionState.Active(sessionId = sessionId, startedAt = 5_000L), state.current)
        }
}
