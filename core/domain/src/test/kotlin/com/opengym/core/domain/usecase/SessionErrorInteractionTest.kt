package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.repository.FakeInProgressSessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when two refusals apply at once.
 *
 * Each use case's own test suite covers its error paths one at a time, which leaves a
 * gap no single suite owns: when two conditions are true together, *which* error comes
 * back, and does the use case stop at the first one or carry on far enough to do damage.
 * Both questions have consequences beyond tidiness — the error a caller receives decides
 * what the UI offers next, and an id inside it decides which session that offer acts on.
 *
 * Nothing here is arbitrary precedence for its own sake: each case names the reason the
 * order is the right way round.
 */
class SessionErrorInteractionTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val templates = FakeTemplateRepository()

    /** A state repository and the four lifecycle use cases wired onto it. */
    private inner class Machine(initial: SessionState = SessionState.Idle) {
        val state = FakeSessionStateRepository(initial)
        val drafts = FakeInProgressSessionRepository()
        private val lock = SessionLifecycleLock()
        val start = StartSessionUseCase(sessions, state, clock, lock)
        val pause = PauseSessionUseCase(sessions, state, clock, lock)
        val resume = ResumeSessionUseCase(sessions, state, clock, lock)
        val finish = FinishSessionUseCase(sessions, state, drafts, clock, lock)
    }

    /** Inserts a session row directly, bypassing the use cases, and returns its id. */
    private suspend fun seedSession(
        isInProgress: Boolean = true,
        completedAt: Long? = null,
    ): Long =
        sessions.insert(
            WorkoutSession(
                isInProgress = isInProgress,
                completedAt = completedAt,
                lastActiveAt = clock.now,
                createdAt = clock.now,
                updatedAt = clock.now,
            ),
        )

    @Test
    fun `finishing a zero-set session that is not the one in progress refuses on the sets`() =
        runTest {
            // Both conditions hold: the state's session has no sets, *and* it is not the
            // session the repository would resolve as active. Finish must answer about the
            // session it was pointed at — falling back to getActive() the way start and
            // resume do would close out a workout the user is still in the middle of.
            val stale = seedSession(isInProgress = false, completedAt = clock.now)
            val running = seedSession()
            sessions.addSet(sessions.addExercise(running))
            val machine = Machine(SessionState.Active(sessionId = stale, startedAt = clock.now))

            val result = machine.finish()

            assertEquals(SessionError.NoSetsLogged, result.exceptionOrNull())
            assertEquals(true, sessions.stored(running)?.isInProgress)
            assertEquals(running, sessions.getActive().first()?.id)
        }

    @Test
    fun `finishing a zero-set session whose row is gone refuses on the missing row`() =
        runTest {
            // Two refusals apply — the row is absent and it has no sets — and the missing row
            // wins because it is the stronger statement: "there is nothing to finish" tells a
            // caller its state is stale, while "log a set first" would invite the user to log
            // sets against a session that no longer exists.
            val deleted = seedSession()
            sessions.delete(deleted)
            val machine = Machine(SessionState.Active(sessionId = deleted, startedAt = clock.now))

            val result = machine.finish()

            assertEquals(SessionError.SessionNotFound(deleted), result.exceptionOrNull())
        }

    @Test
    fun `finishing from a lost state machine refuses even with a session in progress`() =
        runTest {
            // The session is recoverable and has sets, so the *data* is finishable — but the
            // state says nothing is running. Finish refuses rather than adopting the row:
            // resume is the documented way back to a stranded session, and a finish that
            // adopted one would close a workout the user never reopened.
            val running = seedSession()
            sessions.addSet(sessions.addExercise(running))
            val machine = Machine()

            val result = machine.finish()

            assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
            assertEquals(true, sessions.stored(running)?.isInProgress)
            assertTrue("resume is the way back to it", machine.resume().isSuccess)
        }

    @Test
    fun `pausing a paused session whose row is gone refuses on the state`() =
        runTest {
            // The row is missing *and* the state is not Active. The state check runs first,
            // and should: "nothing is running" is what the user did, while a missing row is
            // an integrity problem they cannot act on — and reporting it would make a
            // double-tapped pause look like data loss.
            val deleted = seedSession()
            sessions.delete(deleted)
            val paused = SessionState.Paused(sessionId = deleted, pausedAt = clock.now)
            val machine = Machine(paused)

            val result = machine.pause()

            assertEquals(SessionError.SessionNotActive(paused), result.exceptionOrNull())
            assertEquals(0, sessions.updateCount)
        }

    @Test
    fun `resuming a paused session whose row is gone does not adopt a different one`() =
        runTest {
            // The paused session's row is gone *and* another session is in progress, so the
            // recovery path has something it could adopt. It must not: the state names the
            // session the user paused, and silently resuming a different one would hand them
            // someone else's workout.
            val other = seedSession()
            val machine = Machine(SessionState.Paused(sessionId = 404L, pausedAt = clock.now))

            val result = machine.resume()

            assertEquals(SessionError.SessionNotFound(404L), result.exceptionOrNull())
            assertEquals(SessionState.Paused(sessionId = 404L, pausedAt = clock.now), machine.state.current)
            assertEquals(other, sessions.getActive().first()?.id)
        }

    @Test
    fun `starting while the state and the rows disagree reports the state's session`() =
        runTest {
            // Two sessions look active: one the state machine holds, one in progress on disk.
            // The refusal names the state's, because that is the one the user is looking at
            // and the one a "resume it?" prompt built from this error would act on.
            val onDisk = seedSession()
            val inState = seedSession()
            val machine = Machine(SessionState.Active(sessionId = inState, startedAt = clock.now))

            val result = machine.start()

            assertEquals(SessionError.SessionAlreadyActive(inState), result.exceptionOrNull())
            assertEquals(2, sessions.getAll().first().size)
            assertEquals(true, sessions.stored(onDisk)?.isInProgress)
        }

    @Test
    fun `updating a missing template refuses on the template before validating the name`() =
        runTest {
            // Three refusals apply at once: the template is gone, the name is blank, and the
            // exercise list is empty. Existence wins, and nothing reads the template list —
            // telling a user their name is invalid on a template that no longer exists sends
            // them to fix the wrong thing.
            val updateTemplate = UpdateTemplateUseCase(templates)

            val result = updateTemplate(templateId = 404L, name = "   ", exercises = emptyList())

            assertEquals(TemplateError.NotFound(404L), result.exceptionOrNull())
            assertEquals(0, templates.writeCount)
        }

    @Test
    fun `creating a template refuses on the blank name before the duplicate scan`() =
        runTest {
            // Blank name, empty body, and a name that would collide once trimmed. The name
            // check is both the cheapest and the one the user can act on first, and it
            // short-circuits, so a blank name never pays for a scan of every stored template.
            val createTemplate = CreateTemplateUseCase(templates)
            templates.seed("Push day")

            val result = createTemplate(name = "  ", exercises = emptyList())

            assertEquals(TemplateError.EmptyName, result.exceptionOrNull())
            assertEquals(0, templates.writeCount)
            assertEquals(1, templates.storedCount())
        }

    @Test
    fun `an empty body is refused before the duplicate scan too`() =
        runTest {
            // Named the same as an existing template *and* empty. The body wins: an empty
            // template cannot be created under any name, so renaming it would not help.
            val createTemplate = CreateTemplateUseCase(templates)
            templates.seed("Push day")

            val result = createTemplate(name = "Push day", exercises = emptyList())

            assertEquals(TemplateError.NoExercises, result.exceptionOrNull())
            assertEquals(0, templates.writeCount)
        }

    @Test
    fun `a session refused on every transition at once is left exactly as it was`() =
        runTest {
            // The cross-cutting one: with a stale state pointing at a deleted row, all four
            // transitions are illegal for overlapping reasons. None of them may write.
            val deleted = seedSession()
            sessions.delete(deleted)
            val stale = SessionState.Active(sessionId = deleted, startedAt = clock.now)
            val machine = Machine(stale)

            val results = listOf(machine.start(), machine.pause(), machine.resume(), machine.finish())

            assertTrue(results.all { it.isFailure })
            assertEquals(stale, machine.state.current)
            assertEquals(listOf<SessionState>(stale), machine.state.recorded)
            assertEquals(0, sessions.updateCount)
            assertNull(sessions.getActive().first())
        }
}
