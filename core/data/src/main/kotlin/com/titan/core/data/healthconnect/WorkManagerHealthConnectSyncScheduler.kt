package com.titan.core.data.healthconnect

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.titan.core.data.worker.HealthConnectSyncWorker
import com.titan.core.domain.healthconnect.HealthConnectSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Health Connect write as a one-off [HealthConnectSyncWorker], kept under a unique name per
 * session.
 *
 * ### No constraints
 *
 * The write is small and goes to another app on the same phone, not over a network, so there is
 * nothing to wait for — and a workout that shows up in Health Connect an hour later, when the
 * phone is next charging, is a sync the user would think broken. WorkManager still decides when
 * it runs, which in practice is within seconds, or at the end of a doze window.
 *
 * ### `REPLACE`, so a burst of edits is one write of the last state
 *
 * Renaming a workout just finished is two requests a few seconds apart. [ExistingWorkPolicy.REPLACE]
 * cancels the first if it has not run, or stops it if it is running, and the second reads the
 * row as it is by then. A first write that reaches Health Connect anyway loses to the second,
 * whose version is the later time.
 */
@Singleton
class WorkManagerHealthConnectSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HealthConnectSyncScheduler {

    /**
     * Resolved on first use rather than at construction, for `WorkManagerAutoBackupScheduler`'s
     * reason: the graph is built before anything enqueues, including on test applications with
     * no WorkManager at all.
     */
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override fun requestSync(sessionId: Long) {
        workManager.enqueueUniqueWork(workNameFor(sessionId), ExistingWorkPolicy.REPLACE, request(sessionId))
    }

    private fun request(sessionId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<HealthConnectSyncWorker>()
            .setInputData(workDataOf(HealthConnectSyncWorker.KEY_SESSION_ID to sessionId))
            .build()

    companion object {

        /**
         * The unique name a session's write is enqueued under.
         *
         * A persisted identifier, like the backup's: WorkManager keys pending work on it across
         * launches. Changing it costs little here — work pending under the old name still runs —
         * but for one update that pending work is not replaced by a newer request, and both run.
         */
        fun workNameFor(sessionId: Long): String = "health-connect-sync-$sessionId"
    }
}
