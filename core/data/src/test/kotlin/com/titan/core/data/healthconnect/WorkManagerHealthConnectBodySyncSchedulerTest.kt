package com.titan.core.data.healthconnect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.titan.core.data.worker.HealthConnectBodySyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a body sync request hands WorkManager: one pass per entry, carrying its id and its date,
 * waiting on nothing. Built as [WorkManagerHealthConnectSyncSchedulerTest] is, through a stand-in
 * worker that records what it was handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WorkManagerHealthConnectBodySyncSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)
    private val scheduler = WorkManagerHealthConnectBodySyncScheduler(context)

    /** The id and date pairs the stand-in worker was run for, in order. */
    private val ran = mutableListOf<Pair<Long, Long>>()

    @Before
    fun initialiseWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(RecordingWorkerFactory())
                .build(),
        )
    }

    @Test
    fun `a request runs one pass for that entry with its date, waiting on nothing`() {
        scheduler.requestSync(measurementId = 3L, measuredAt = 1_000L)

        assertEquals(listOf(3L to 1_000L), ran)
        val work = workManager.getWorkInfosForUniqueWork(
            WorkManagerHealthConnectBodySyncScheduler.workNameFor(3L),
        ).get()
        assertEquals(WorkInfo.State.SUCCEEDED, work.single().state)
        assertFalse(work.single().constraints.requiresCharging())
        assertFalse(work.single().constraints.requiresDeviceIdle())
    }

    @Test
    fun `an entry can be synced again after its first pass has run`() {
        scheduler.requestSync(measurementId = 3L, measuredAt = 1_000L)
        scheduler.requestSync(measurementId = 3L, measuredAt = 1_000L)
        scheduler.requestSync(measurementId = 4L, measuredAt = 2_000L)

        assertEquals(listOf(3L to 1_000L, 3L to 1_000L, 4L to 2_000L), ran)
    }

    @Test
    fun `a body pass is not named like a session's`() {
        // One id can be a session and a measurement both, and neither may replace the other.
        assertFalse(
            WorkManagerHealthConnectBodySyncScheduler.workNameFor(3L) ==
                WorkManagerHealthConnectSyncScheduler.workNameFor(3L),
        )
    }

    private inner class RecordingWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            assertEquals(HealthConnectBodySyncWorker::class.java.name, workerClassName)
            return object : Worker(appContext, workerParameters) {
                override fun doWork(): Result {
                    ran += inputData.getLong(HealthConnectBodySyncWorker.KEY_MEASUREMENT_ID, 0L) to
                        inputData.getLong(HealthConnectBodySyncWorker.KEY_MEASURED_AT, 0L)
                    return Result.success()
                }
            }
        }
    }
}
