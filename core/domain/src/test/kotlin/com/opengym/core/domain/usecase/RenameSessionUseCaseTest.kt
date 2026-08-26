package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What a session ends up called.
 *
 * The rules worth pinning down are all about the boundary between `null` and `""`: the schema
 * makes them different states on purpose — `null` is what the date-derived fallback title keys
 * off — and every screen that sets a name hands over a text field that can be blank.
 */
class RenameSessionUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val renameSession = RenameSessionUseCase(sessions)

    private suspend fun startNamed(name: String? = "Push day"): Long = startSession(name = name).getOrThrow().sessionId

    private suspend fun stored(sessionId: Long): WorkoutSession? = sessions.getById(sessionId).first()

    @Test
    fun `renaming writes the new name`() =
        runTest {
            val sessionId = startNamed()

            assertEquals("Leg day", renameSession(sessionId, "Leg day").getOrThrow())
            assertEquals("Leg day", stored(sessionId)?.name)
        }

    @Test
    fun `the name is trimmed`() =
        runTest {
            val sessionId = startNamed()

            assertEquals("Leg day", renameSession(sessionId, "  Leg day  ").getOrThrow())
        }

    @Test
    fun `a blank name clears it rather than storing whitespace`() =
        runTest {
            // The only way back to unnamed, and the reason this takes the raw string: a session
            // titled `" "` is one whose date fallback never fires again.
            val sessionId = startNamed()

            assertNull(renameSession(sessionId, "   ").getOrThrow())
            assertNull(stored(sessionId)?.name)
        }

    @Test
    fun `an empty name clears it`() =
        runTest {
            val sessionId = startNamed()

            assertNull(renameSession(sessionId, "").getOrThrow())
            assertNull(stored(sessionId)?.name)
        }

    @Test
    fun `null clears it`() =
        runTest {
            val sessionId = startNamed()

            assertNull(renameSession(sessionId, null).getOrThrow())
        }

    @Test
    fun `naming a session that was started without one works`() =
        runTest {
            val sessionId = startNamed(name = null)

            assertEquals("Push day", renameSession(sessionId, "Push day").getOrThrow())
        }

    @Test
    fun `a name that comes out the same as the stored one writes nothing`() =
        runTest {
            // Not an optimisation for its own sake: every write re-emits the session to every
            // observer, and a rename dialog confirmed without an edit is the ordinary case.
            val sessionId = startNamed()
            val writesBefore = sessions.updateCount

            assertEquals("Push day", renameSession(sessionId, "  Push day ").getOrThrow())

            assertEquals(writesBefore, sessions.updateCount)
        }

    @Test
    fun `the session's own timestamps are left alone`() =
        runTest {
            // A title is not a change to what was performed, which is what a session's timestamps
            // are read for. Matches the recap's note save.
            val sessionId = startNamed()
            clock.advance(60_000L)

            renameSession(sessionId, "Leg day").getOrThrow()

            val row = stored(sessionId)
            assertEquals(5_000L, row?.updatedAt)
            assertEquals(5_000L, row?.createdAt)
        }

    @Test
    fun `a session that has been finished can still be renamed`() =
        runTest {
            // No state check, unlike every other use case here: a workout logged last March is
            // renamed the same way as the one in progress, and it is by definition not active.
            val sessionId = startNamed()
            val session = requireNotNull(stored(sessionId))
            sessions.update(session.copy(isInProgress = false, completedAt = 6_000L))

            assertEquals("Leg day", renameSession(sessionId, "Leg day").getOrThrow())
        }

    @Test
    fun `renaming a session that does not exist is refused`() =
        runTest {
            val result = renameSession(sessionId = 404L, name = "Leg day")

            assertEquals(SessionError.SessionNotFound(404L), result.exceptionOrNull())
        }

    @Test
    fun `the refusal surfaces as a descriptive IllegalStateException`() =
        runTest {
            val refused = renameSession(sessionId = 404L, name = "Leg day")

            val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }

            assertEquals("session 404 does not exist", thrown.message)
        }
}
