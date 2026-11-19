package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.WorkoutActivity
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.FinishedSessionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncToHealthConnectUseCaseTest {

    private val healthConnect = FakeHealthConnect()
    private val sessions = FakeHealthConnectSessionRepository()
    private val sync = SyncToHealthConnectUseCase(healthConnect, sessions)

    private fun finished(
        id: Long = 7L,
        createdAt: Long = 1_000_000L,
        completedAt: Long = 4_600_000L,
        name: String? = "Push day",
        notes: String? = "felt strong",
        disciplines: List<Discipline?> = listOf(Discipline.STRENGTH),
        healthConnectId: String? = null,
    ) = FinishedSessionActivity(
        session = WorkoutSession(
            id = id,
            name = name,
            notes = notes,
            createdAt = createdAt,
            completedAt = completedAt,
            isInProgress = false,
            lastActiveAt = completedAt,
            updatedAt = completedAt + 5,
            healthConnectId = healthConnectId,
            healthConnectActivity = healthConnectId?.let { WorkoutActivity.RUNNING },
        ),
        disciplines = disciplines,
    ).also { sessions.sessions += it }

    @Test
    fun `a finished session is written with its span, name, notes and activity`() =
        runTest {
            finished(disciplines = listOf(Discipline.POWERLIFTING))

            val outcome = sync(7L).getOrThrow()

            assertEquals(HealthConnectSyncOutcome.WRITTEN, outcome)
            val expected = HealthConnectSession(
                startedAt = 1_000_000L,
                endedAt = 4_600_000L,
                activity = WorkoutActivity.WEIGHTLIFTING,
                title = "Push day",
                notes = "felt strong",
            )
            assertEquals(listOf(FakeHealthConnect.Write(7L, 4_600_005L, expected)), healthConnect.written)
        }

    @Test
    fun `the record is versioned by the row's updated_at so a later write wins`() =
        runTest {
            finished()

            sync(7L)

            assertEquals(4_600_005L, healthConnect.written.single().version)
        }

    @Test
    fun `an unnamed session is written with no title`() =
        runTest {
            finished(name = null)

            sync(7L)

            assertEquals(null, healthConnect.written.single().session.title)
        }

    @Test
    fun `a session stamped as finishing before it started is written a second long`() =
        runTest {
            // A backwards clock adjustment mid-workout. Health Connect refuses an empty span.
            finished(createdAt = 10_000L, completedAt = 9_000L)

            sync(7L)

            val written = healthConnect.written.single().session
            assertEquals(10_000L, written.startedAt)
            assertEquals(11_000L, written.endedAt)
        }

    @Test
    fun `nothing is written without the write permission`() =
        runTest {
            finished()
            healthConnect.granted = setOf(HealthConnectAccess.READ)

            val error = sync(7L).exceptionOrNull()

            assertEquals(HealthConnectError.NotPermitted(HealthConnectAccess.WRITE), error)
            assertTrue(healthConnect.written.isEmpty())
        }

    @Test
    fun `nothing is written where Health Connect is not available`() =
        runTest {
            finished()
            healthConnect.availability = HealthConnectAvailability.NOT_INSTALLED

            val error = sync(7L).exceptionOrNull()

            assertEquals(HealthConnectError.Unavailable(HealthConnectAvailability.NOT_INSTALLED), error)
            assertTrue(healthConnect.written.isEmpty())
        }

    @Test
    fun `a session that is not finished is not written and is not a failure`() =
        runTest {
            assertEquals(HealthConnectSyncOutcome.NOT_FINISHED, sync(99L).getOrThrow())
            assertTrue(healthConnect.written.isEmpty())
        }

    @Test
    fun `a session imported from Health Connect is never written back`() =
        runTest {
            finished(healthConnectId = "abc", disciplines = emptyList())

            assertEquals(HealthConnectSyncOutcome.IMPORTED, sync(7L).getOrThrow())
            assertTrue(healthConnect.written.isEmpty())
        }

    @Test
    fun `a provider failure comes back in the result`() =
        runTest {
            finished()
            val failure = IllegalStateException("quota exceeded")
            healthConnect.failure = failure

            assertEquals(failure, sync(7L).exceptionOrNull())
        }

    @Test
    fun `a cancellation is rethrown rather than reported`() =
        runTest {
            finished()
            healthConnect.failure = CancellationException("the caller went away")

            val thrown = runCatching { sync(7L) }.exceptionOrNull()

            assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
        }
}
