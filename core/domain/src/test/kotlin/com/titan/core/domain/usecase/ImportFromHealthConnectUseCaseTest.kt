package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.healthconnect.HealthConnectRecord
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class ImportFromHealthConnectUseCaseTest {

    private val now = 100.days.inWholeMilliseconds
    private val clock = MutableClock(now = now)
    private val healthConnect = FakeHealthConnect()
    private val sessions = FakeHealthConnectSessionRepository()
    private val import = ImportFromHealthConnectUseCase(healthConnect, sessions, clock)

    private val anHourAgo = now - 1.hours.inWholeMilliseconds
    private val yesterday = now - 1.days.inWholeMilliseconds

    private fun record(
        id: String,
        startedAt: Long = anHourAgo,
        activity: WorkoutActivity = WorkoutActivity.RUNNING,
        title: String? = "Morning run",
        notes: String? = null,
        writtenByThisApp: Boolean = false,
    ) = HealthConnectRecord(
        id = id,
        session = HealthConnectSession(
            startedAt = startedAt,
            endedAt = startedAt + 30 * 60_000L,
            activity = activity,
            title = title,
            notes = notes,
        ),
        writtenByThisApp = writtenByThisApp,
    ).also { healthConnect.records += it }

    private fun logged(
        createdAt: Long,
        disciplines: List<Discipline?> = listOf(Discipline.STRENGTH),
    ) {
        sessions.sessions += FinishedSessionActivity(
            session = WorkoutSession(
                id = sessions.sessions.size + 1L,
                createdAt = createdAt,
                completedAt = createdAt + 3_600_000L,
                isInProgress = false,
                lastActiveAt = createdAt + 3_600_000L,
                updatedAt = createdAt + 3_600_000L,
            ),
            disciplines = disciplines,
        )
    }

    @Test
    fun `a record from another app becomes a finished session with no sets`() =
        runTest {
            record(id = "run-1", notes = "easy pace")

            val result = import().getOrThrow()

            assertEquals(HealthConnectImport(imported = 1, skipped = 0), result)
            val stored = sessions.imported.single()
            assertEquals("Morning run", stored.name)
            assertEquals("easy pace", stored.notes)
            assertEquals(anHourAgo, stored.createdAt)
            assertEquals(anHourAgo + 30 * 60_000L, stored.completedAt)
            assertEquals(anHourAgo + 30 * 60_000L, stored.lastActiveAt)
            assertEquals(false, stored.isInProgress)
            assertEquals(now, stored.updatedAt)
            assertEquals("run-1", stored.healthConnectId)
            assertEquals(WorkoutActivity.RUNNING, stored.healthConnectActivity)
        }

    @Test
    fun `a blank title is stored as no name, so the history draws the date instead`() =
        runTest {
            record(id = "run-1", title = "   ")

            import()

            assertEquals(null, sessions.imported.single().name)
        }

    @Test
    fun `it reads the last thirty days`() =
        runTest {
            import()

            assertEquals(listOf(now - 30.days.inWholeMilliseconds until now + 1), healthConnect.reads)
        }

    @Test
    fun `a record Titan wrote itself is skipped`() =
        runTest {
            record(id = "mine", writtenByThisApp = true)

            val result = import().getOrThrow()

            assertEquals(HealthConnectImport(imported = 0, skipped = 1), result)
            assertTrue(sessions.imported.isEmpty())
        }

    @Test
    fun `importing twice creates no duplicates`() =
        runTest {
            record(id = "run-1")
            record(id = "ride-1", startedAt = yesterday, activity = WorkoutActivity.BIKING)

            import()
            val second = import().getOrThrow()

            assertEquals(HealthConnectImport(imported = 0, skipped = 2), second)
            assertEquals(2, sessions.imported.size)
            // The second run inserts nothing at all, rather than leaning on the unique index.
            assertEquals(1, sessions.insertBatches.size)
        }

    @Test
    fun `a record starting with a logged session of the same activity is skipped`() =
        runTest {
            logged(createdAt = anHourAgo, disciplines = listOf(Discipline.STRENGTH))
            record(id = "watch-1", activity = WorkoutActivity.STRENGTH_TRAINING)

            val result = import().getOrThrow()

            assertEquals(HealthConnectImport(imported = 0, skipped = 1), result)
        }

    @Test
    fun `a record starting with a logged session of another activity is imported`() =
        runTest {
            logged(createdAt = anHourAgo, disciplines = listOf(Discipline.STRENGTH))
            record(id = "run-1", activity = WorkoutActivity.RUNNING)

            assertEquals(1, import().getOrThrow().imported)
        }

    @Test
    fun `a record of the same activity a millisecond apart from a logged session is imported`() =
        runTest {
            logged(createdAt = anHourAgo)
            record(id = "watch-1", startedAt = anHourAgo + 1, activity = WorkoutActivity.STRENGTH_TRAINING)

            assertEquals(1, import().getOrThrow().imported)
        }

    @Test
    fun `two apps recording the same workout import it once`() =
        runTest {
            record(id = "watch", activity = WorkoutActivity.RUNNING)
            record(id = "phone", activity = WorkoutActivity.RUNNING)

            val result = import().getOrThrow()

            assertEquals(HealthConnectImport(imported = 1, skipped = 1), result)
        }

    @Test
    fun `a record duplicating an earlier import from another app is skipped`() =
        runTest {
            record(id = "watch", activity = WorkoutActivity.RUNNING)
            import()
            record(id = "phone", activity = WorkoutActivity.RUNNING)

            assertEquals(0, import().getOrThrow().imported)
        }

    @Test
    fun `nothing is read without the read permission`() =
        runTest {
            record(id = "run-1")
            healthConnect.granted = setOf(HealthConnectAccess.WRITE)

            val error = import().exceptionOrNull()

            assertEquals(HealthConnectError.NotPermitted(HealthConnectAccess.READ), error)
            assertTrue(healthConnect.reads.isEmpty())
        }

    @Test
    fun `nothing is read where Health Connect is not available`() =
        runTest {
            healthConnect.availability = HealthConnectAvailability.UNSUPPORTED

            val error = import().exceptionOrNull()

            assertEquals(HealthConnectError.Unavailable(HealthConnectAvailability.UNSUPPORTED), error)
        }

    @Test
    fun `a provider failure comes back in the result and stores nothing`() =
        runTest {
            val failure = IllegalStateException("provider died")
            healthConnect.failure = failure

            assertEquals(failure, import().exceptionOrNull())
            assertTrue(sessions.imported.isEmpty())
        }

    @Test
    fun `a cancellation is rethrown rather than reported`() =
        runTest {
            healthConnect.failure = CancellationException("the screen went away")

            val thrown = runCatching { import() }.exceptionOrNull()

            assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
        }
}
