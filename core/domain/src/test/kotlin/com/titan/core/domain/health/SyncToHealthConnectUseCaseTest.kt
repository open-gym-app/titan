package com.titan.core.domain.health

import com.titan.core.domain.feature.M8Feature
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthSkipReason
import com.titan.core.domain.model.HealthSyncOutcome
import com.titan.core.domain.model.HealthWorkout
import com.titan.core.domain.model.HealthWorkoutType
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.usecase.FakeSessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncToHealthConnectUseCaseTest {

    private val sessions = FakeSessionRepository()
    private val gateway = FakeHealthConnectGateway()
    private val toggles = FakeFeatureToggles(M8Feature.HEALTH_CONNECT)
    private val sync = SyncToHealthConnectUseCase(sessions, gateway, toggles)

    private suspend fun finished(
        createdAt: Long = 1_000,
        completedAt: Long = 4_000,
        name: String? = "Push",
    ): Long =
        sessions.insert(
            WorkoutSession(
                name = name,
                notes = "felt good",
                completedAt = completedAt,
                isInProgress = false,
                lastActiveAt = completedAt,
                createdAt = createdAt,
                updatedAt = completedAt,
            ),
        )

    @Test
    fun `a finished session is written as a strength workout`() =
        runTest {
            val id = finished()

            val outcome = sync(id).getOrThrow()

            assertEquals(HealthSyncOutcome.Synced, outcome)
            assertEquals(
                HealthWorkout(
                    startMillis = 1_000,
                    endMillis = 4_000,
                    type = HealthWorkoutType.STRENGTH_TRAINING,
                    title = "Push",
                    notes = "felt good",
                    externalId = HealthWorkout.externalIdFor(id),
                ),
                gateway.written.single(),
            )
            assertTrue(gateway.written.single().isOwn)
        }

    @Test
    fun `a session that ends no later than it began is given a positive length`() =
        runTest {
            val id = finished(createdAt = 5_000, completedAt = 5_000)

            sync(id).getOrThrow()

            assertEquals(5_001, gateway.written.single().endMillis)
        }

    @Test
    fun `nothing is written while the toggle is off`() =
        runTest {
            toggles.setEnabled(M8Feature.HEALTH_CONNECT, false)

            assertSkipped(HealthSkipReason.DISABLED, finished())
        }

    @Test
    fun `nothing is written when health connect is unavailable`() =
        runTest {
            gateway.availability = HealthAvailability.NOT_INSTALLED

            assertSkipped(HealthSkipReason.UNAVAILABLE, finished())
        }

    @Test
    fun `nothing is written without permission`() =
        runTest {
            gateway.permitted = false

            assertSkipped(HealthSkipReason.NO_PERMISSION, finished())
        }

    @Test
    fun `an unknown session is skipped`() =
        runTest {
            assertSkipped(HealthSkipReason.SESSION_NOT_FOUND, 99)
        }

    @Test
    fun `a session still in progress is skipped`() =
        runTest {
            val id = sessions.insert(WorkoutSession(lastActiveAt = 1, createdAt = 1, updatedAt = 1))

            assertSkipped(HealthSkipReason.SESSION_NOT_FINISHED, id)
        }

    @Test
    fun `a failed write is returned as a failure`() =
        runTest {
            gateway.failWrites = true

            assertTrue(sync(finished()).isFailure)
        }

    private suspend fun assertSkipped(
        reason: HealthSkipReason,
        sessionId: Long,
    ) {
        assertEquals(HealthSyncOutcome.Skipped(reason), sync(sessionId).getOrThrow())
        assertTrue(gateway.written.isEmpty())
    }
}
