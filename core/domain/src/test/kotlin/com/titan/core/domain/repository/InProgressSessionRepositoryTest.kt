package com.titan.core.domain.repository

import com.titan.core.domain.model.ActiveExercise
import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.STARTED_AT
import com.titan.core.domain.model.maximalSession
import com.titan.core.domain.model.minimalSession
import com.titan.core.domain.timer.RestTimerSnapshot
import com.titan.core.domain.timer.RestTimerState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The [InProgressSessionRepository] contract, exercised through the stub every domain
 * test uses.
 *
 * These are tests of the *contract*, not of the fake: `:core:data`'s implementation has
 * to satisfy the same statements, and its own suite should assert them again against a
 * real store — including the one thing an in-memory `var` cannot fail, the atomicity of
 * [InProgressSessionRepository.save].
 */
class InProgressSessionRepositoryTest {

    private val repository = FakeInProgressSessionRepository()

    @Test
    fun `loading before anything is saved returns no draft`() =
        runTest {
            assertNull(repository.load())
        }

    @Test
    fun `a saved draft loads back as itself`() =
        runTest {
            val session = maximalSession()

            repository.save(session)

            assertEquals(session, repository.load())
        }

    @Test
    fun `saving overwrites the previous draft rather than accumulating`() =
        runTest {
            repository.save(minimalSession())
            val later = minimalSession().copy(
                exercises = listOf(ActiveExercise(exerciseId = 4, sets = listOf(ActiveSet(reps = 5)))),
            )

            repository.save(later)

            assertEquals(later, repository.load())
        }

    @Test
    fun `loading twice returns the same draft`() =
        runTest {
            repository.save(maximalSession())

            assertEquals(repository.load(), repository.load())
        }

    @Test
    fun `clearing leaves no draft to restore`() =
        runTest {
            repository.save(maximalSession())

            repository.clear()

            assertNull(repository.load())
        }

    @Test
    fun `clearing twice is not an error`() =
        runTest {
            repository.save(maximalSession())

            repository.clear()
            repository.clear()

            assertNull(repository.load())
            assertEquals(2, repository.clearCount)
        }

    @Test
    fun `clearing before anything is saved is not an error`() =
        runTest {
            repository.clear()

            assertNull(repository.load())
        }

    @Test
    fun `a draft can be saved again after being cleared`() =
        runTest {
            repository.save(maximalSession())
            repository.clear()

            repository.save(minimalSession())

            assertEquals(minimalSession(), repository.load())
        }

    @Test
    fun `a stored document this build cannot read is no draft`() =
        runTest {
            repository.corrupt("""{"version":9999,"session":{}}""")

            assertNull(repository.load())
        }

    @Test
    fun `a truncated document is no draft rather than a crash`() =
        runTest {
            repository.save(maximalSession())

            repository.corrupt(repository.stored!!.take(40))

            assertNull(repository.load())
        }

    @Test
    fun `restoring a draft recovers the session state the user left`() =
        runTest {
            val session = maximalSession()
            repository.save(session)

            val restored = repository.load()

            assertEquals(session.toSessionState(), restored?.toSessionState())
            assertEquals(session.currentExercise, restored?.currentExercise)
        }

    @Test
    fun `a draft restored long after it was written restores a rest that is over`() =
        runTest {
            val savedAt = STARTED_AT + 60_000
            repository.save(
                minimalSession().copy(
                    restTimer = RestTimerSnapshot.of(RestTimerState.Running(45.seconds, 90.seconds), savedAt),
                ),
            )

            val restored = repository.load()

            assertEquals(
                RestTimerState.Running(15.seconds, 90.seconds),
                restored?.restTimerStateAt(savedAt + 30_000),
            )
            assertEquals(
                RestTimerState.Finished,
                restored?.restTimerStateAt(savedAt + 10.minutes.inWholeMilliseconds),
            )
        }

    @Test
    fun `a seeded stub loads the draft it was built with`() =
        runTest {
            val seeded = FakeInProgressSessionRepository(maximalSession())

            assertEquals(maximalSession(), seeded.load())
            assertEquals(0, seeded.saveCount)
        }
}
