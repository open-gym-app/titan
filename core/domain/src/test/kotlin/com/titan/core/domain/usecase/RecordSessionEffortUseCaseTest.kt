package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a workout is recorded as having burned, and how it was rated. */
class RecordSessionEffortUseCaseTest {

    private val sessions = FakeSessionRepository()
    private val recordEffort = RecordSessionEffortUseCase(sessions)

    private suspend fun finished(): Long =
        sessions.insert(
            WorkoutSession(
                completedAt = 2_000,
                isInProgress = false,
                lastActiveAt = 2_000,
                createdAt = 1_000,
                updatedAt = 2_000,
            ),
        )

    private suspend fun stored(id: Long): WorkoutSession? = sessions.getById(id).first()

    @Test
    fun `both figures are written, on a session long finished`() =
        runTest {
            val id = finished()

            recordEffort(id, calories = 520, rating = 4).getOrThrow()

            assertEquals(520, stored(id)?.calories)
            assertEquals(4, stored(id)?.rating)
        }

    @Test
    fun `null clears a figure`() =
        runTest {
            val id = finished()
            recordEffort(id, calories = 520, rating = 4).getOrThrow()

            recordEffort(id, calories = null, rating = 4).getOrThrow()

            assertNull(stored(id)?.calories)
        }

    @Test
    fun `the timestamps are left alone, as a rename leaves them`() =
        runTest {
            val id = finished()

            recordEffort(id, calories = 520, rating = 4).getOrThrow()

            assertEquals(2_000L, stored(id)?.updatedAt)
        }

    @Test
    fun `a rating outside one to five is refused and nothing is written`() =
        runTest {
            val id = finished()

            val failure = recordEffort(id, calories = 520, rating = 6).exceptionOrNull()

            assertTrue(failure is SessionError.EffortRejected)
            assertNull(stored(id)?.calories)
        }

    @Test
    fun `calories out of bounds are refused`() =
        runTest {
            val id = finished()

            assertTrue(recordEffort(id, calories = 0, rating = null).exceptionOrNull() is SessionError.EffortRejected)
            assertTrue(
                recordEffort(id, calories = 50_001, rating = null).exceptionOrNull() is SessionError.EffortRejected,
            )
        }

    @Test
    fun `a session that no longer exists is reported as such`() =
        runTest {
            assertTrue(recordEffort(99, calories = 1, rating = 1).exceptionOrNull() is SessionError.SessionNotFound)
        }
}
