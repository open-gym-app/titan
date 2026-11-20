package com.titan.core.data.healthconnect

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.titan.core.data.worker.HealthConnectBodySyncWorker
import com.titan.core.domain.healthconnect.HealthConnectBodySyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A body measurement's Health Connect pass as a one-off [HealthConnectBodySyncWorker], under a
 * unique name per entry.
 *
 * Built the way [WorkManagerHealthConnectSyncScheduler] is, for its reasons: no constraints,
 * because the write goes to another app on the same phone and should land within seconds, and
 * [ExistingWorkPolicy.REPLACE], so a burst of requests for one entry — a delete and its undo, a
 * save and a quick correction — ends in one pass over the entry as it stands by then.
 */
@Singleton
class WorkManagerHealthConnectBodySyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HealthConnectBodySyncScheduler {

    /** Resolved on first use, for [WorkManagerHealthConnectSyncScheduler]'s reason. */
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override fun requestSync(
        measurementId: Long,
        measuredAt: Long,
    ) {
        workManager.enqueueUniqueWork(
            workNameFor(measurementId),
            ExistingWorkPolicy.REPLACE,
            request(measurementId, measuredAt),
        )
    }

    private fun request(
        measurementId: Long,
        measuredAt: Long,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<HealthConnectBodySyncWorker>()
            .setInputData(
                workDataOf(
                    HealthConnectBodySyncWorker.KEY_MEASUREMENT_ID to measurementId,
                    HealthConnectBodySyncWorker.KEY_MEASURED_AT to measuredAt,
                ),
            )
            .build()

    companion object {

        /**
         * The unique name an entry's pass is enqueued under — a persisted identifier, as
         * [WorkManagerHealthConnectSyncScheduler.workNameFor] is. By id alone, so a request
         * replaces any still pending for the same entry; the date travels in the input data.
         */
        fun workNameFor(measurementId: Long): String = "health-connect-body-sync-$measurementId"
    }
}
