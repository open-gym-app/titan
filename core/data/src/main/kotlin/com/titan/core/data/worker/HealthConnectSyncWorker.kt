package com.titan.core.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.titan.core.domain.healthconnect.HealthConnectError
import com.titan.core.domain.usecase.SyncToHealthConnectUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Writes one session to Health Connect: the queued half of
 * [com.titan.core.domain.healthconnect.HealthConnectSyncScheduler].
 *
 * Everything about *whether* to write is [SyncToHealthConnectUseCase]'s, and runs here rather
 * than when the work was asked for — so a grant given or taken back in between is the one that
 * counts, and the session is written as it stands now, not as it stood then.
 *
 * ### Which failures are retried
 *
 * A refusal — Health Connect not installed, the write not granted — is the ordinary state of
 * most users, and a retry would only be refused again. It ends the work as a success: there is
 * nothing wrong, just nothing to do. A throw from the provider is different: a provider process
 * that died mid-call, or a rate limit, is usually gone a minute later. Those are retried under
 * WorkManager's default backoff, [MAX_RUN_ATTEMPTS] times in all, and then dropped. Nothing is
 * shown either way, for the reason `BackupWorker` shows nothing: a background failure is not
 * something the user can act on when it interrupts them, and the next edit or finish writes the
 * record again.
 */
@HiltWorker
class HealthConnectSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val syncToHealthConnect: SyncToHealthConnectUseCase,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, NO_SESSION)
        // A request built by something other than the scheduler. Retrying it would only fail
        // the same way.
        if (sessionId == NO_SESSION) return Result.failure()

        // A cancellation is rethrown by the use case, not returned, so it never reaches here
        // as a failure to retry.
        val failure = syncToHealthConnect(sessionId).exceptionOrNull()
        return when {
            failure == null || failure is HealthConnectError -> Result.success()
            runAttemptCount + 1 < MAX_RUN_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {

        /** The input key the session id travels under. */
        const val KEY_SESSION_ID: String = "session_id"

        /**
         * Attempts one write gets before it is dropped. [runAttemptCount] is zero on the first
         * run, so the comparison in [doWork] counts *this* attempt.
         */
        const val MAX_RUN_ATTEMPTS: Int = 3

        /** Not a session id: Room hands out ids from one. */
        private const val NO_SESSION = 0L
    }
}
