package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.repository.FakeBodyMeasurementRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBodyToHealthConnectUseCaseTest {

    private val healthConnect = FakeHealthConnect()
    private val measurements = FakeBodyMeasurementRepository(
        listOf(
            BodyMeasurement(
                id = ID,
                weight = 80_500_000L,
                waist = 820,
                bodyFat = 155,
                createdAt = MEASURED_AT,
                updatedAt = UPDATED_AT,
            ),
        ),
    )
    private val sync = SyncBodyToHealthConnectUseCase(healthConnect, measurements)

    @Test
    fun `an entry is written as a weight and a body fat, versioned by updated_at`() =
        runTest {
            assertEquals(HealthConnectBodySyncOutcome.WRITTEN, sync(ID, MEASURED_AT).getOrThrow())

            assertEquals(
                listOf(
                    FakeHealthConnect.BodyWrite(
                        ID,
                        UPDATED_AT,
                        HealthConnectBodyReading(HealthConnectBodyMetric.WEIGHT, MEASURED_AT, 80_500_000L),
                    ),
                    FakeHealthConnect.BodyWrite(
                        ID,
                        UPDATED_AT,
                        HealthConnectBodyReading(HealthConnectBodyMetric.BODY_FAT, MEASURED_AT, 155L),
                    ),
                ),
                healthConnect.bodyWrites,
            )
            assertTrue(healthConnect.bodyDeletes.isEmpty())
        }

    @Test
    fun `an edit is written under the same key with the later version`() =
        runTest {
            sync(ID, MEASURED_AT)
            val edited = measurements.get(ID)!!.copy(weight = 79_000_000L, updatedAt = UPDATED_AT + 60_000L)
            measurements.update(edited)

            sync(ID, MEASURED_AT)

            val weights = healthConnect.bodyWrites.filter { it.reading.metric == HealthConnectBodyMetric.WEIGHT }
            assertEquals(listOf(ID, ID), weights.map { it.measurementId })
            assertEquals(listOf(MEASURED_AT, MEASURED_AT), weights.map { it.reading.measuredAt })
            assertEquals(listOf(UPDATED_AT, UPDATED_AT + 60_000L), weights.map { it.version })
        }

    @Test
    fun `a figure cleared in an edit has its record removed`() =
        runTest {
            measurements.update(measurements.get(ID)!!.copy(bodyFat = null))

            sync(ID, MEASURED_AT)

            assertEquals(listOf(HealthConnectBodyMetric.WEIGHT), healthConnect.bodyWrites.map { it.reading.metric })
            assertEquals(
                listOf(FakeHealthConnect.BodyDelete(ID, MEASURED_AT, HealthConnectBodyMetric.BODY_FAT)),
                healthConnect.bodyDeletes,
            )
        }

    @Test
    fun `a deleted entry has both records removed`() =
        runTest {
            measurements.delete(ID)

            assertEquals(HealthConnectBodySyncOutcome.REMOVED, sync(ID, MEASURED_AT).getOrThrow())

            assertTrue(healthConnect.bodyWrites.isEmpty())
            assertEquals(
                listOf(HealthConnectBodyMetric.WEIGHT, HealthConnectBodyMetric.BODY_FAT),
                healthConnect.bodyDeletes.map { it.metric },
            )
            assertTrue(healthConnect.bodyDeletes.all { it.measurementId == ID && it.measuredAt == MEASURED_AT })
        }

    @Test
    fun `a new entry under a reused id does not overwrite the old one's records`() =
        runTest {
            // "Delete all data" hands id 3 out again, to a measurement taken later.
            measurements.update(measurements.get(ID)!!.copy(createdAt = MEASURED_AT + 86_400_000L))

            // The job for the old entry removes the old records and writes nothing.
            assertEquals(HealthConnectBodySyncOutcome.REMOVED, sync(ID, MEASURED_AT).getOrThrow())
            assertTrue(healthConnect.bodyWrites.isEmpty())
            assertTrue(healthConnect.bodyDeletes.all { it.measuredAt == MEASURED_AT })
        }

    @Test
    fun `only the figures whose write is granted are touched`() =
        runTest {
            healthConnect.granted = setOf(HealthConnectAccess.WRITE_BODY_FAT)
            measurements.update(measurements.get(ID)!!.copy(weight = null))

            sync(ID, MEASURED_AT)

            assertEquals(listOf(HealthConnectBodyMetric.BODY_FAT), healthConnect.bodyWrites.map { it.reading.metric })
            assertTrue(healthConnect.bodyDeletes.isEmpty())
        }

    @Test
    fun `an entry imported from Health Connect is not written back`() =
        runTest {
            measurements.update(measurements.get(ID)!!.copy(fromHealthConnect = true))

            assertEquals(HealthConnectBodySyncOutcome.IMPORTED, sync(ID, MEASURED_AT).getOrThrow())
            assertTrue(healthConnect.bodyWrites.isEmpty())
            assertTrue(healthConnect.bodyDeletes.isEmpty())
        }

    @Test
    fun `nothing is written when neither figure may be written`() =
        runTest {
            healthConnect.granted = setOf(
                HealthConnectAccess.WRITE_EXERCISE,
                HealthConnectAccess.READ_WEIGHT,
                HealthConnectAccess.READ_BODY_FAT,
            )

            val error = sync(ID, MEASURED_AT).exceptionOrNull()

            assertEquals(HealthConnectError.NotPermitted(HealthConnectAccess.WRITE_WEIGHT), error)
            assertTrue(healthConnect.bodyWrites.isEmpty())
        }

    @Test
    fun `nothing is written when Health Connect is not installed`() =
        runTest {
            healthConnect.availability = HealthConnectAvailability.NOT_INSTALLED

            val error = sync(ID, MEASURED_AT).exceptionOrNull()

            assertEquals(HealthConnectError.Unavailable(HealthConnectAvailability.NOT_INSTALLED), error)
        }

    @Test
    fun `a provider failure comes back in the result`() =
        runTest {
            val failure = IllegalStateException("quota")
            healthConnect.failure = failure

            assertEquals(failure, sync(ID, MEASURED_AT).exceptionOrNull())
        }

    @Test(expected = CancellationException::class)
    fun `a cancellation is rethrown, not returned`() =
        runTest {
            healthConnect.failure = CancellationException("left the screen")

            sync(ID, MEASURED_AT)
            Unit
        }

    private companion object {
        const val ID = 3L
        const val MEASURED_AT = 1_700_000_000_000L
        const val UPDATED_AT = 1_700_000_100_000L
    }
}
