package com.titan.core.domain.health

import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectException
import com.titan.core.domain.model.HealthSkipReason
import com.titan.core.domain.model.HealthSyncOutcome
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.HealthConnectGateway
import com.titan.core.domain.usecase.FakeSessionRepository
import com.titan.core.domain.usecase.MutableClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportFromHealthConnectUseCaseTest {

    private val day = 86_400_000L
    private val now = 100 * day
    private val sessions = FakeSessionRepository()
    private val gateway = FakeHealthConnectGateway()
    private val toggles = FakeFeatureToggles(M8Feature.HEALTH_CONNECT)
    private val import = ImportFromHealthConnectUseCase(sessions, gateway, toggles, MutableClock(now))

    private fun workout(
        start: Long,
        type: HealthWorkoutType = HealthWorkoutType.STRENGTH_TRAINING,
        externalId: String? = null,
        title: String? = "Legs",
    ) = HealthWorkout(start, start + 3_600_000, type, title, "from watch", externalId)

    private suspend fun allSessions() = sessions.getAll().first()

    @Test
    fun `reads the past thirty days`() =
        runTest {
            import().getOrThrow()

            assertEquals((now - 30 * day) to now, gateway.lastRange)
        }

    @Test
    fun `imports a strength workout as a finished session`() =
        runTest {
            gateway.stored = listOf(workout(start = now - day))

            val outcome = import().getOrThrow()

            assertEquals(HealthSyncOutcome.Imported(imported = 1, skipped = 0), outcome)
            val session = allSessions().single()
            assertEquals("Legs", session.name)
            assertEquals("from watch", session.notes)
            assertEquals(now - day, session.createdAt)
            assertEquals(now - day + 3_600_000, session.completedAt)
            assertEquals(false, session.isInProgress)
            assertEquals(now, session.updatedAt)
        }

    @Test
    fun `a blank title leaves the session unnamed`() =
        runTest {
            gateway.stored = listOf(workout(start = now - day, title = "  "))

            import().getOrThrow()

            assertNull(allSessions().single().name)
        }

    @Test
    fun `skips a workout starting where a stored session starts`() =
        runTest {
            sessions.insert(
                WorkoutSession(
                    completedAt = now,
                    isInProgress = false,
                    lastActiveAt = now,
                    createdAt = now - day + 30_000,
                    updatedAt = now,
                ),
            )
            gateway.stored = listOf(workout(start = now - day))

            assertEquals(HealthSyncOutcome.Imported(0, 1), import().getOrThrow())
            assertEquals(1, allSessions().size)
        }

    @Test
    fun `a session in progress does not hide a workout`() =
        runTest {
            sessions.insert(WorkoutSession(lastActiveAt = 1, createdAt = now - day, updatedAt = 1))
            gateway.stored = listOf(workout(start = now - day))

            assertEquals(HealthSyncOutcome.Imported(1, 0), import().getOrThrow())
        }

    @Test
    fun `a start further apart than the tolerance is a different workout`() =
        runTest {
            sessions.insert(
                WorkoutSession(
                    completedAt = now,
                    isInProgress = false,
                    lastActiveAt = now,
                    createdAt = now - day + 120_000,
                    updatedAt = now,
                ),
            )
            gateway.stored = listOf(workout(start = now - day))

            assertEquals(HealthSyncOutcome.Imported(1, 0), import().getOrThrow())
        }

    @Test
    fun `skips other workout types and the app's own records`() =
        runTest {
            gateway.stored = listOf(
                workout(start = now - 3 * day, type = HealthWorkoutType.OTHER),
                workout(start = now - 2 * day, externalId = HealthWorkout.externalIdFor(7)),
                workout(start = now - day, externalId = "someone-else"),
            )

            assertEquals(HealthSyncOutcome.Imported(1, 2), import().getOrThrow())
        }

    @Test
    fun `importing twice adds nothing the second time, and duplicates in one batch collapse`() =
        runTest {
            gateway.stored = listOf(workout(start = now - day), workout(start = now - day + 1_000))

            assertEquals(HealthSyncOutcome.Imported(1, 1), import().getOrThrow())
            assertEquals(HealthSyncOutcome.Imported(0, 2), import().getOrThrow())
            assertEquals(1, allSessions().size)
        }

    @Test
    fun `does nothing while the toggle is off`() =
        runTest {
            toggles.setEnabled(M8Feature.HEALTH_CONNECT, false)
            gateway.stored = listOf(workout(start = now - day))

            assertEquals(HealthSyncOutcome.Skipped(HealthSkipReason.DISABLED), import().getOrThrow())
            assertTrue(allSessions().isEmpty())
        }

    @Test
    fun `does nothing when unavailable or not permitted`() =
        runTest {
            gateway.availability = HealthAvailability.UNSUPPORTED
            assertEquals(HealthSyncOutcome.Skipped(HealthSkipReason.UNAVAILABLE), import().getOrThrow())

            gateway.availability = HealthAvailability.AVAILABLE
            gateway.permitted = false
            assertEquals(HealthSyncOutcome.Skipped(HealthSkipReason.NO_PERMISSION), import().getOrThrow())
        }

    @Test
    fun `a read failure is returned as a failure`() =
        runTest {
            val failing = ImportFromHealthConnectUseCase(
                sessions,
                object : HealthConnectGateway by gateway {
                    override suspend fun readBetween(
                        sinceMillis: Long,
                        untilMillis: Long,
                    ): List<HealthWorkout> = throw HealthConnectException("boom")
                },
                toggles,
                MutableClock(now),
            )

            assertTrue(failing().isFailure)
        }
}
