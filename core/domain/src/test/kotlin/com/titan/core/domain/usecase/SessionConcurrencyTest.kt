package com.titan.core.domain.usecase

import com.titan.core.domain.repository.FakeInProgressSessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lifecycle transitions are read-modify-write cycles over two repositories, so
 * "one session at a time" only holds if they cannot interleave. These tests park one
 * caller mid-transition and let a second one race it, which is what a double-tapped
 * button does.
 *
 * Each test gets its own [SessionLifecycleLock] rather than the shared one, so the
 * serialization under test is the one being constructed here.
 */
class SessionConcurrencyTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val drafts = FakeInProgressSessionRepository()
    private val lock = SessionLifecycleLock()
    private val startSession = StartSessionUseCase(sessions, state, clock, lock)
    private val finishSession = FinishSessionUseCase(sessions, state, drafts, clock, lock)

    @Test
    fun `a second start cannot slip in between the first one's check and insert`() =
        runTest {
            val reachedInsert = CompletableDeferred<Unit>()
            val releaseInsert = CompletableDeferred<Unit>()
            sessions.beforeInsert = {
                sessions.beforeInsert = null // only the first caller parks
                reachedInsert.complete(Unit)
                releaseInsert.await()
            }

            val first = async { startSession() }
            reachedInsert.await() // the first start is now mid-transition
            val second = async { startSession() } // the racing double-tap
            releaseInsert.complete(Unit)

            val results = listOf(first.await(), second.await())
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            assertEquals(1, sessions.getAll().first().size)
        }

    @Test
    fun `the loser of a start race is told a session is already active`() =
        runTest {
            val reachedInsert = CompletableDeferred<Unit>()
            val releaseInsert = CompletableDeferred<Unit>()
            sessions.beforeInsert = {
                sessions.beforeInsert = null
                reachedInsert.complete(Unit)
                releaseInsert.await()
            }

            val first = async { startSession() }
            reachedInsert.await()
            val second = async { startSession() }
            releaseInsert.complete(Unit)

            first.await()
            val refusal = second.await().exceptionOrNull()
            assertEquals(
                "cannot start a session while session 1 is still in progress",
                refusal?.message,
            )
        }

    @Test
    fun `concurrent finishes close the session out once`() =
        runTest {
            val started = startSession().getOrThrow()
            sessions.addSet(sessions.addExercise(started.sessionId))
            val updatesBefore = sessions.updateCount

            val results = listOf(async { finishSession() }, async { finishSession() })
                .map { it.await() }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            assertEquals(updatesBefore + 1, sessions.updateCount)
        }
}
