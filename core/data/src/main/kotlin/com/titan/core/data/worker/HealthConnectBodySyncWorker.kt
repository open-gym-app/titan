package com.titan.core.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.usecase.SyncBodyToHealthConnectUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Brings one body measurement's Health Connect records up to date: the queued half of
 * [com.titan.core.domain.healthconnect.HealthConnectBodySyncScheduler].
 *
 * The same contract as [HealthConnectSyncWorker], for its reasons: whether to write is
 * [SyncBodyToHealthConnectUseCase]'s, decided when the work runs; a refusal is a success, since a
 * retry would be refused again; a throw from the provider is retried, [MAX_RUN_ATTEMPTS] times in
 * all, and then dropped without a word.
 */
@HiltWorker
class HealthConnectBodySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val syncBodyToHealthConnect: SyncBodyToHealthConnectUseCase,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val measurementId = inputData.getLong(KEY_MEASUREMENT_ID, NO_MEASUREMENT)
        val measuredAt = inputData.getLong(KEY_MEASURED_AT, NO_TIME)
        // A request built by something other than the scheduler. Retrying it would only fail the
        // same way — and without the time, there is no key to write or remove under.
        if (measurementId == NO_MEASUREMENT || measuredAt == NO_TIME) return Result.failure()

        val failure = syncBodyToHealthConnect(measurementId, measuredAt).exceptionOrNull()
        return when {
            failure == null || failure is HealthConnectError -> Result.success()
            runAttemptCount + 1 < MAX_RUN_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {

        /** The input key the measurement id travels under. */
        const val KEY_MEASUREMENT_ID: String = "measurement_id"

        /** The input key the measurement's `created_at` travels under. */
        const val KEY_MEASURED_AT: String = "measured_at"

        /** Attempts one pass gets before it is dropped — [HealthConnectSyncWorker.MAX_RUN_ATTEMPTS]. */
        const val MAX_RUN_ATTEMPTS: Int = HealthConnectSyncWorker.MAX_RUN_ATTEMPTS

        /** Not a measurement id: Room hands out ids from one. */
        private const val NO_MEASUREMENT = 0L

        /** Not a `created_at`: an export bounds every one to the epoch and after. */
        private const val NO_TIME = Long.MIN_VALUE
    }
}
