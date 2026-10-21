package com.titan.core.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.titan.core.data.worker.BackupWorker
import com.titan.core.domain.backup.AutoBackupScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The weekly backup as a `PeriodicWorkRequest`, kept under one unique name.
 *
 * ### WorkManager rather than an alarm, and why that is the opposite call from the rest timer
 *
 * A reminder is an appointment: it has to arrive punctually or it is worthless, which is what
 * an exact alarm is for and what #39 uses. A backup is the other kind of job entirely. It does
 * not care whether it runs at 03:00 or at 09:00 on Sunday — it cares that it eventually runs,
 * that it survives a reboot and an app update with nothing re-registering it, and that it does
 * not spin the disk while the user is mid-workout on 6% battery. WorkManager gives all three;
 * an exact alarm gives none of them and asks for a permission besides.
 *
 * ### The constraints are the point, not a tuning knob
 *
 * Charging, idle and battery-not-low together say "run this when the phone is on a charger and
 * nobody is holding it", which for most people is overnight. What they cost is punctuality —
 * a phone that is never charged overnight backs up late — and that is precisely the thing this
 * job is allowed to trade away. What they buy is that a full export of a 50,000-set history is
 * never the reason a workout stutters or a battery dies at the gym.
 *
 * ### No backoff criteria, because an idle job may not have any
 *
 * `PeriodicWorkRequest.Builder` refuses `setBackoffCriteria` on a request that requires device
 * idle, and it refuses it because the platform does: `JobInfo.Builder.build()` throws on the
 * combination. So a retry runs under WorkManager's own default backoff rather than a figure
 * chosen here — which loses nothing worth having. What a backoff tunes is how soon the second
 * attempt comes, and the second attempt cannot come before the phone is idle and charging
 * again whatever it says. `BackupWorker` is where the retries are actually bounded, at three.
 *
 * ### `KEEP`, so a schedule is not restarted by being looked at
 *
 * [ExistingPeriodicWorkPolicy.KEEP] leaves an already-enqueued schedule exactly as it is.
 * `REPLACE`/`UPDATE` would restart the seven-day window on every call, and [schedule] is called
 * whenever the toggle is set to on — including when it is already on. A phone whose owner opens
 * the backup screen most weeks would then never reach the end of a period, and the backup that
 * "runs weekly" would never run at all.
 */
@Singleton
class WorkManagerAutoBackupScheduler @Inject constructor(@param:ApplicationContext private val context: Context) :
    AutoBackupScheduler {

    /**
     * Resolved on first use rather than at construction.
     *
     * `WorkManager.getInstance` throws when WorkManager has not been initialised, and this
     * class is a `@Singleton` in a graph that is built long before anything wants to schedule
     * anything — the DI binding tests resolve it on a Robolectric application that has no
     * WorkManager at all. Deferring means the platform is only touched by a toggle actually
     * being flipped.
     */
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override fun schedule() {
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
    }

    override fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<BackupWorker>(INTERVAL_DAYS, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()

    companion object {

        /**
         * The unique name the schedule is enqueued under.
         *
         * A persisted identifier: WorkManager keys the enqueued work on it across launches and
         * reboots, so renaming it strands the schedule on every device that already has one —
         * the old work keeps running under the old name and [cancel] no longer reaches it.
         */
        const val WORK_NAME: String = "auto-backup"

        private const val INTERVAL_DAYS = 7L
    }
}
