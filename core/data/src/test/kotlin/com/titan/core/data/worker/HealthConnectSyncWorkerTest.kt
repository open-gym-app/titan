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
import com.titan.core.domain.healthconnect.HealthConnectRecord
import com.titan.core.domain.healthconnect.HealthConnectSession
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.FinishedSessionActivity
import com.titan.core.domain.repository.HealthConnectSessionRepository
import com.titan.core.domain.usecase.SyncToHealthConnectUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the queued write reports back to WorkManager.
 *
 * Whether to write is `SyncToHealthConnectUseCase`'s subject and tested there; what is left here
 * is the mapping from its answer to a [ListenableWorker.Result] — which decides whether a user
 * who never connected Health Connect costs a retry loop on every finish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HealthConnectSyncWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val healthConnect = WritingHealthConnect()

    @Test
    fun `a session that was written is a success`() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(listOf(SESSION_ID), healthConnect.written)
    }

    @Test
    fun `a write that is not granted is a success, not a retry`() {
        // The state of most users. A retry would be refused again, on every finish.
        healthConnect.granted = emptySet()

        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(healthConnect.written.isEmpty())
    }

    @Test
    fun `a Health Connect that is not installed is a success, not a retry`() {
        healthConnect.availability = HealthConnectAvailability.NOT_INSTALLED

        assertEquals(ListenableWorker.Result.success(), run())
    }

    @Test
    fun `a provider that throws is retried`() {
        healthConnect.failure = IllegalStateException("the provider went away")

        assertEquals(ListenableWorker.Result.retry(), run(runAttemptCount = 0))
    }

    @Test
    fun `a provider that throws on the last attempt is dropped`() {
        healthConnect.failure = IllegalStateException("the provider went away")

        assertEquals(
            ListenableWorker.Result.failure(),
            run(runAttemptCount = HealthConnectSyncWorker.MAX_RUN_ATTEMPTS - 1),
        )
    }

    @Test
    fun `a request with no session is dropped without asking Health Connect`() {
        assertEquals(ListenableWorker.Result.failure(), run(sessionId = null))
        assertTrue(healthConnect.written.isEmpty())
    }

    private fun run(
        sessionId: Long? = SESSION_ID,
        runAttemptCount: Int = 0,
    ): ListenableWorker.Result {
        val sync = SyncToHealthConnectUseCase(healthConnect, OneFinishedSession()) { NOW }
        val worker = TestListenableWorkerBuilder<HealthConnectSyncWorker>(context)
            .setInputData(
                sessionId?.let { workDataOf(HealthConnectSyncWorker.KEY_SESSION_ID to it) } ?: workDataOf(),
            )
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = HealthConnectSyncWorker(appContext, workerParameters, sync)
                },
            )
            .build()
        return runBlocking { worker.doWork() }
    }

    /** Health Connect as the session ids written to it. */
    private class WritingHealthConnect : HealthConnect {

        var availability = HealthConnectAvailability.AVAILABLE
        var granted: Set<HealthConnectAccess> = HealthConnectAccess.entries.toSet()
        var failure: Throwable? = null
        val written = mutableListOf<Long>()

        override fun availability(): HealthConnectAvailability = availability

        override val permissions: Set<String> = emptySet()

        override suspend fun grantedAccess(): Set<HealthConnectAccess> = granted

        override suspend fun writeSession(
            sessionId: Long,
            version: Long,
            session: HealthConnectSession,
        ) {
            failure?.let { throw it }
            written += sessionId
        }

        override suspend fun readSessions(
            startedFrom: Long,
            startedUntil: Long,
        ): List<HealthConnectRecord> = error("the sync never reads")

        override suspend fun revokeAccess() = Unit
    }

    /** The one finished session [SESSION_ID]. */
    private class OneFinishedSession : HealthConnectSessionRepository {

        override suspend fun getFinished(sessionId: Long): FinishedSessionActivity? =
            FinishedSessionActivity(
                session = WorkoutSession(
                    id = SESSION_ID,
                    completedAt = NOW,
                    isInProgress = false,
                    lastActiveAt = NOW,
                    createdAt = NOW - 3_600_000L,
                    updatedAt = NOW,
                ),
                disciplines = listOf(null),
            ).takeIf { sessionId == SESSION_ID }

        override suspend fun getFinishedStartedBetween(
            startedFrom: Long,
            startedUntil: Long,
        ): List<FinishedSessionActivity> = error("the sync never imports")

        override suspend fun insertImported(sessions: List<WorkoutSession>): Int = error("the sync never imports")
    }

    private companion object {
        const val SESSION_ID = 7L
        const val NOW = 10_000_000L
    }
}
