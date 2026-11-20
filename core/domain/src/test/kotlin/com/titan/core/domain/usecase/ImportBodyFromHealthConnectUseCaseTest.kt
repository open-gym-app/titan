package com.titan.core.domain.usecase

import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectBodyRecord
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.model.BodyMeasurement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class ImportBodyFromHealthConnectUseCaseTest {

    private val now = 100.days.inWholeMilliseconds
    private val clock = MutableClock(now = now)
    private val healthConnect = FakeHealthConnect()
    private val measurements = FakeBodyMeasurementRepository()
    private val import = ImportBodyFromHealthConnectUseCase(healthConnect, measurements, clock)

    private val anHourAgo = now - 1.hours.inWholeMilliseconds
    private val yesterday = now - 1.days.inWholeMilliseconds

    private var nextRecordId = 1

    private fun record(
        metric: HealthConnectBodyMetric,
        value: Long,
        measuredAt: Long = anHourAgo,
        writtenByThisApp: Boolean = false,
    ) = HealthConnectBodyRecord(
        id = "record-${nextRecordId++}",
        reading = HealthConnectBodyReading(metric = metric, measuredAt = measuredAt, value = value),
        writtenByThisApp = writtenByThisApp,
    ).also { healthConnect.bodyRecords += it }

    private fun weight(
        value: Long = 80_000_000L,
        measuredAt: Long = anHourAgo,
        writtenByThisApp: Boolean = false,
    ) = record(HealthConnectBodyMetric.WEIGHT, value, measuredAt, writtenByThisApp)

    private fun bodyFat(
        value: Long = 180L,
        measuredAt: Long = anHourAgo,
    ) = record(HealthConnectBodyMetric.BODY_FAT, value, measuredAt)

    @Test
    fun `a weight from another app becomes an imported entry`() =
        runTest {
            weight()

            assertEquals(HealthConnectBodyImport(imported = 1, skipped = 0), import().getOrThrow())

            val stored = measurements.all.single()
            assertEquals(80_000_000L, stored.weight)
            assertNull(stored.bodyFat)
            assertEquals(anHourAgo, stored.createdAt)
            assertEquals(now, stored.updatedAt)
            assertTrue(stored.fromHealthConnect)
        }

    @Test
    fun `a weight and a body fat at one instant become one entry`() =
        runTest {
            weight()
            bodyFat()
            bodyFat(value = 175L, measuredAt = yesterday)

            assertEquals(HealthConnectBodyImport(imported = 2, skipped = 0), import().getOrThrow())

            val atOnce = measurements.all.single { it.createdAt == anHourAgo }
            assertEquals(80_000_000L, atOnce.weight)
            assertEquals(180, atOnce.bodyFat)
            assertEquals(175, measurements.all.single { it.createdAt == yesterday }.bodyFat)
        }

    @Test
    fun `a second import adds nothing`() =
        runTest {
            weight()
            bodyFat()
            import()

            assertEquals(HealthConnectBodyImport(imported = 0, skipped = 2), import().getOrThrow())
            assertEquals(1, measurements.all.size)
            assertEquals(1, measurements.insertBatches.size)
        }

    @Test
    fun `Titan's own records are not imported`() =
        runTest {
            weight(writtenByThisApp = true)

            assertEquals(HealthConnectBodyImport(imported = 0, skipped = 1), import().getOrThrow())
            assertTrue(measurements.all.isEmpty())
        }

    @Test
    fun `a figure already logged at the same instant is skipped, another figure is not`() =
        runTest {
            measurements.insert(BodyMeasurement(weight = 79_000_000L, createdAt = anHourAgo, updatedAt = anHourAgo))
            weight()
            bodyFat()

            assertEquals(HealthConnectBodyImport(imported = 1, skipped = 1), import().getOrThrow())

            val imported = measurements.all.single { it.fromHealthConnect }
            assertNull(imported.weight)
            assertEquals(180, imported.bodyFat)
        }

    @Test
    fun `a body fat for an instant an earlier import brought a weight in for joins that entry`() =
        runTest {
            weight()
            import()
            // The scale's app wrote the body fat after the first import ran.
            bodyFat()

            assertEquals(HealthConnectBodyImport(imported = 1, skipped = 1), import().getOrThrow())

            val entry = measurements.all.single()
            assertEquals(80_000_000L, entry.weight)
            assertEquals(180, entry.bodyFat)
            assertTrue(entry.fromHealthConnect)
        }

    @Test
    fun `two apps writing one weigh-in bring it in once`() =
        runTest {
            weight(value = 80_000_000L)
            weight(value = 80_100_000L)

            assertEquals(HealthConnectBodyImport(imported = 1, skipped = 1), import().getOrThrow())
        }

    @Test
    fun `a figure out of the form's bounds is skipped`() =
        runTest {
            weight(value = 0L)
            bodyFat(value = 1_001L)

            assertEquals(HealthConnectBodyImport(imported = 0, skipped = 2), import().getOrThrow())
            assertTrue(measurements.insertBatches.isEmpty())
        }

    @Test
    fun `only the figures whose read is granted are read`() =
        runTest {
            healthConnect.granted = setOf(HealthConnectAccess.READ_BODY_FAT)
            weight()
            bodyFat()

            assertEquals(HealthConnectBodyImport(imported = 1, skipped = 0), import().getOrThrow())
            assertNull(measurements.all.single().weight)
        }

    @Test
    fun `the window is the last thirty days`() =
        runTest {
            weight(measuredAt = now - 31.days.inWholeMilliseconds)
            weight(measuredAt = now)

            assertEquals(HealthConnectBodyImport(imported = 1, skipped = 0), import().getOrThrow())
            assertEquals(now, measurements.all.single().createdAt)
        }

    @Test
    fun `nothing is read when neither figure may be read`() =
        runTest {
            healthConnect.granted = setOf(HealthConnectAccess.READ_EXERCISE, HealthConnectAccess.WRITE_WEIGHT)

            val error = import().exceptionOrNull()

            assertEquals(HealthConnectError.NotPermitted(HealthConnectAccess.READ_WEIGHT), error)
        }

    @Test
    fun `nothing is read when Health Connect is not installed`() =
        runTest {
            healthConnect.availability = HealthConnectAvailability.NOT_INSTALLED

            val error = import().exceptionOrNull()

            assertEquals(HealthConnectError.Unavailable(HealthConnectAvailability.NOT_INSTALLED), error)
        }

    @Test
    fun `a provider failure comes back in the result`() =
        runTest {
            val failure = IllegalStateException("provider went away")
            healthConnect.failure = failure

            assertEquals(failure, import().exceptionOrNull())
        }

    @Test(expected = CancellationException::class)
    fun `a cancellation is rethrown, not returned`() =
        runTest {
            healthConnect.failure = CancellationException("left the screen")

            import()
            Unit
        }
}
