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
import com.titan.core.data.worker.HealthConnectSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a sync request hands WorkManager: one write per session, carrying its id, waiting on
 * nothing.
 *
 * Work with no constraints runs the moment the test WorkManager is given it, so what is asserted
 * is what *ran* — through a stand-in worker that records the session it was handed. The real
 * worker's mapping of outcomes is `HealthConnectSyncWorkerTest`'s subject.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WorkManagerHealthConnectSyncSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)
    private val scheduler = WorkManagerHealthConnectSyncScheduler(context)

    /** The session ids the stand-in worker was run for, in order. */
    private val ran = mutableListOf<Long>()

    @Before
    fun initialiseWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                // Synchronous, and a plain `Worker` below rather than a coroutine one, so the
                // work has finished by the time the request returns.
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(RecordingWorkerFactory())
                .build(),
        )
    }

    @Test
    fun `a request runs one write for that session, waiting on nothing`() {
        scheduler.requestSync(7L)

        assertEquals(listOf(7L), ran)
        val work = workFor(7L).single()
        assertEquals(WorkInfo.State.SUCCEEDED, work.state)
        assertFalse(work.constraints.requiresCharging())
        assertFalse(work.constraints.requiresDeviceIdle())
        assertFalse(work.constraints.requiresBatteryNotLow())
        assertFalse(work.constraints.requiresStorageNotLow())
    }

    @Test
    fun `requests for different sessions are kept apart`() {
        scheduler.requestSync(7L)
        scheduler.requestSync(8L)

        assertEquals(listOf(7L, 8L), ran)
        assertEquals(1, workFor(7L).size)
        assertEquals(1, workFor(8L).size)
    }

    @Test
    fun `a session can be written again after its first write has run`() {
        // A rename after the finish's write: the unique name must not swallow the second.
        scheduler.requestSync(7L)
        scheduler.requestSync(7L)

        assertEquals(listOf(7L, 7L), ran)
    }

    private fun workFor(sessionId: Long): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerHealthConnectSyncScheduler.workNameFor(sessionId)).get()

    /** Builds a worker that records its session id and succeeds, in place of the real one. */
    private inner class RecordingWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            assertEquals(HealthConnectSyncWorker::class.java.name, workerClassName)
            return object : Worker(appContext, workerParameters) {
                override fun doWork(): Result {
                    ran += inputData.getLong(HealthConnectSyncWorker.KEY_SESSION_ID, 0L)
                    return Result.success()
                }
            }
        }
    }
}
