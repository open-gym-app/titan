package com.opengym.core.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * What the scheduler actually enqueues, read back off WorkManager's own store.
 *
 * The constraints and the period are the design of the feature rather than parameters of it —
 * "weekly, while charging and idle" is the promise the backup screen makes and the reason this
 * is deferrable work instead of an alarm — so they are asserted rather than left to a code
 * review. `WorkManagerTestInitHelper` gives a real WorkManager backed by an in-memory database
 * on the JVM, which is what makes that possible without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WorkManagerAutoBackupSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager: WorkManager get() = WorkManager.getInstance(context)
    private val scheduler = WorkManagerAutoBackupScheduler(context)

    @Before
    fun initialiseWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            // Synchronous, so an enqueue has landed by the time the call returns and the
            // assertions below are not racing WorkManager's own executor.
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `turning it on enqueues one weekly job`() {
        scheduler.schedule()

        val work = enqueued()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
        assertEquals(
            TimeUnit.DAYS.toMillis(7),
            work.single().periodicityInfo?.repeatIntervalMillis,
        )
    }

    /**
     * The constraints, which are the whole reason this is a job and not an alarm.
     *
     * A full export of a 50,000-set history must never be the reason a workout stutters or a
     * battery dies at the gym, and the price — a phone that is never charged overnight backs up
     * late — is the one thing a backup is allowed to trade away.
     *
     * The idle constraint is also why the request carries no backoff criteria of its own:
     * `PeriodicWorkRequest.Builder` throws on the pair, because `JobInfo` does. This test is
     * what caught that, and it is why building the request is part of what it asserts.
     */
    @Test
    fun `the job waits for a charging, idle phone with battery to spare`() {
        scheduler.schedule()

        val constraints = enqueued().single().constraints
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresDeviceIdle())
        assertTrue(constraints.requiresBatteryNotLow())
        // Nothing here goes anywhere near a network, and asking for one would keep a backup
        // from running on a phone in flight mode for no reason at all.
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
    }

    /**
     * `KEEP`, and what it protects.
     *
     * The toggle calls this whenever it is set to on, including when it already was. Replacing
     * the work would restart the seven-day window each time, so a phone whose owner opens the
     * backup screen most weeks would never reach the end of a period — a weekly backup that
     * never runs. The unchanged id is what says the original request survived.
     */
    @Test
    fun `scheduling again keeps the schedule that is already running`() {
        scheduler.schedule()
        val first = enqueued().single().id

        scheduler.schedule()

        val work = enqueued()
        assertEquals(1, work.size)
        assertEquals(first, work.single().id)
    }

    @Test
    fun `turning it off cancels the work`() {
        scheduler.schedule()

        scheduler.cancel()

        assertTrue(enqueued().all { it.state == WorkInfo.State.CANCELLED })
    }

    /** Cancelling what was never scheduled is not an error, per the port's contract. */
    @Test
    fun `cancelling nothing is not an error`() {
        scheduler.cancel()

        assertFalse(enqueued().any { it.state == WorkInfo.State.ENQUEUED })
    }

    private fun enqueued(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WorkManagerAutoBackupScheduler.WORK_NAME).get()
}
