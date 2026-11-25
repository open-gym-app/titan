package com.titan.core.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.healthconnect.HealthConnectBodyMetric
import com.titan.core.domain.healthconnect.HealthConnectBodyReading
import com.titan.core.domain.healthconnect.HealthConnectBodyRecord
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.usecase.SyncBodyToHealthConnectUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the queued body pass reports back to WorkManager — the mapping
 * [HealthConnectSyncWorkerTest] pins for sessions, held to here for body measurements. Whether to
 * write is `SyncBodyToHealthConnectUseCase`'s subject and tested there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HealthConnectBodySyncWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val healthConnect = BodyWritingHealthConnect()

    @Test
    fun `an entry that was written is a success`() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(listOf(MEASUREMENT_ID), healthConnect.written)
    }

    @Test
    fun `a write that is not granted is a success, not a retry`() {
        healthConnect.granted = emptySet()

        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(healthConnect.written.isEmpty())
    }

    @Test
    fun `a provider that throws is retried, then dropped`() {
        healthConnect.failure = IllegalStateException("the provider went away")

        assertEquals(ListenableWorker.Result.retry(), run(runAttemptCount = 0))
        assertEquals(
            ListenableWorker.Result.failure(),
            run(runAttemptCount = HealthConnectBodySyncWorker.MAX_RUN_ATTEMPTS - 1),
        )
    }

    @Test
    fun `a request missing its id or its date is dropped without asking Health Connect`() {
        assertEquals(ListenableWorker.Result.failure(), run(measurementId = null))
        assertEquals(ListenableWorker.Result.failure(), run(measuredAt = null))
        assertTrue(healthConnect.written.isEmpty())
    }

    private fun run(
        measurementId: Long? = MEASUREMENT_ID,
        measuredAt: Long? = MEASURED_AT,
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result {
        val sync = SyncBodyToHealthConnectUseCase(healthConnect, OneMeasurement())
        val input = listOfNotNull(
            measurementId?.let { HealthConnectBodySyncWorker.KEY_MEASUREMENT_ID to it },
            measuredAt?.let { HealthConnectBodySyncWorker.KEY_MEASURED_AT to it },
        )
        val worker = TestListenableWorkerBuilder<HealthConnectBodySyncWorker>(context)
            .setInputData(workDataOf(*input.toTypedArray()))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = HealthConnectBodySyncWorker(appContext, workerParameters, sync)
                },
            )
            .build()
        return runBlocking { worker.doWork() }
    }

    /** Health Connect as the measurement ids written to it. */
    private class BodyWritingHealthConnect : HealthConnect {

        var granted: Set<HealthConnectAccess> = HealthConnectAccess.entries.toSet()
        var failure: Throwable? = null
        val written = mutableListOf<Long>()

        override fun availability(): HealthConnectAvailability = HealthConnectAvailability.AVAILABLE

        override val permissions: Set<String> = emptySet()

        override suspend fun grantedAccess(): Set<HealthConnectAccess> = granted

        override suspend fun writeSession(
            sessionId: Long,
            version: Long,
            session: HealthConnectSession,
        ) = error("the body sync never writes a session")

        override suspend fun readSessions(
            startedFrom: Long,
            startedUntil: Long,
        ): List<HealthConnectRecord> = error("the sync never reads")

        override suspend fun writeBodyReading(
            measurementId: Long,
            version: Long,
            reading: HealthConnectBodyReading,
        ) {
            failure?.let { throw it }
            if (measurementId !in written) written += measurementId
        }

        override suspend fun deleteBodyReading(
            measurementId: Long,
            measuredAt: Long,
            metric: HealthConnectBodyMetric,
        ) {
            failure?.let { throw it }
        }

        override suspend fun readBodyReadings(
            metric: HealthConnectBodyMetric,
            measuredFrom: Long,
            measuredUntil: Long,
        ): List<HealthConnectBodyRecord> = error("the sync never reads")

        override val canRevokeAccess: Boolean = true

        override suspend fun revokeAccess() = Unit
    }

    /** The one entry [MEASUREMENT_ID], a weight and a body fat. */
    private class OneMeasurement : BodyMeasurementRepository {

        private val entry = BodyMeasurement(
            id = MEASUREMENT_ID,
            weight = 80_000_000L,
            bodyFat = 150,
            createdAt = MEASURED_AT,
            updatedAt = MEASURED_AT,
        )

        override suspend fun get(id: Long): BodyMeasurement? = entry.takeIf { id == MEASUREMENT_ID }

        override fun getAll(): Flow<List<BodyMeasurement>> = flowOf(listOf(entry))

        override fun getLatest(): Flow<BodyMeasurement?> = flowOf(entry)

        override suspend fun getMeasuredBetween(
            measuredFrom: Long,
            measuredUntil: Long,
        ): List<BodyMeasurement> = error("the sync never imports")

        override suspend fun insert(measurement: BodyMeasurement): Long = error("the sync never writes the history")

        override suspend fun insertAll(measurements: List<BodyMeasurement>) = error("the sync never imports")

        override suspend fun update(measurement: BodyMeasurement) = error("the sync never writes the history")

        override suspend fun delete(id: Long) = error("the sync never writes the history")
    }

    private companion object {
        const val MEASUREMENT_ID = 3L
        const val MEASURED_AT = 10_000_000L
    }
}
