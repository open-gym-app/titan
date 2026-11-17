package com.titan.core.data.importer

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.titan.core.data.di.IoDispatcher
import com.titan.core.data.worker.HistoryImportWorker
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportRun
import com.titan.core.domain.importer.HistoryImportRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HistoryImportRunner] over WorkManager: one unique piece of work, [HistoryImportWorker], and
 * what WorkManager records about it read back as a [HistoryImportRun].
 *
 * ### The document stays readable after the screen is gone
 *
 * The system picker grants this app a read of the picked document for as long as the process
 * lives. An import outlives the screen, and one the system interrupts is offered again after
 * the process is gone, so [start] takes the grant persistably — `ACTION_OPEN_DOCUMENT` grants
 * are persistable — and [dismiss] gives it back once the user has read how the import ended.
 * A provider that refuses a persistable grant still leaves the process-long one, which covers
 * every import that is not interrupted. The app keeps no other persisted grant, so [start]
 * also releases any a previous import left behind.
 *
 * ### WorkManager on demand, off the main thread
 *
 * Resolved on first use, like `WorkManagerAutoBackupScheduler`'s, so building the graph touches
 * nothing; and every call is on [ioDispatcher], because the first one in a process initialises
 * WorkManager, which opens its database.
 */
@Singleton
class WorkManagerHistoryImportRunner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notifications: HistoryImportNotifications,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HistoryImportRunner {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override fun observe(): Flow<HistoryImportRun> =
        flow { emitAll(workManager.getWorkInfosForUniqueWorkFlow(HistoryImportWork.WORK_NAME)) }
            .map { infos -> HistoryImportWork.runOf(infos.lastOrNull(), HistoryImportWorker.isRunningHere) }
            .distinctUntilChanged()
            // The contract is a flow that never fails: a WorkManager that cannot be read has
            // nothing to show, and the screen offers a fresh start rather than a crash.
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(HistoryImportRun.None)
            }
            .flowOn(ioDispatcher)

    override suspend fun start(request: HistoryImportRequest) {
        withContext(ioDispatcher) {
            if (current() is HistoryImportRun.Running) return@withContext
            holdOnly(request.document)
            notifications.clearFinished()
            // `REPLACE`, not `KEEP`: a running import was refused above, so what this replaces is
            // a finished one or one left `ENQUEUED` by a process that died — which would only
            // fail as interrupted when it ran.
            workManager.enqueueUniqueWork(HistoryImportWork.WORK_NAME, ExistingWorkPolicy.REPLACE, workFor(request))
        }
    }

    override suspend fun cancel() {
        withContext(ioDispatcher) { workManager.cancelUniqueWork(HistoryImportWork.WORK_NAME) }
    }

    override suspend fun dismiss() {
        withContext(ioDispatcher) {
            if (current() is HistoryImportRun.Running) return@withContext
            holdOnly(null)
            notifications.clearFinished()
            // Finished work is all this app has besides the weekly backup, which is periodic
            // and never finished, so pruning removes exactly the import's record.
            workManager.pruneWork()
        }
    }

    private suspend fun current(): HistoryImportRun =
        HistoryImportWork.runOf(
            workManager.getWorkInfosForUniqueWorkFlow(HistoryImportWork.WORK_NAME).first().lastOrNull(),
            HistoryImportWorker.isRunningHere,
        )

    /**
     * Keeps a persisted read grant on [document] and on nothing else. Each step is best-effort:
     * a grant that cannot be taken or released changes what can be retried later, never whether
     * this import starts.
     */
    private fun holdOnly(document: String?) {
        val resolver = context.contentResolver
        resolver.persistedUriPermissions
            .filter { it.uri.toString() != document }
            .forEach { granted -> runCatching { resolver.releasePersistableUriPermission(granted.uri, READ) } }
        if (document != null) runCatching { resolver.takePersistableUriPermission(document.toUri(), READ) }
    }

    private fun workFor(request: HistoryImportRequest): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<HistoryImportWorker>()
            .setInputData(HistoryImportWork.inputOf(request))
            .apply { HistoryImportWork.tagsOf(request).forEach(::addTag) }
            .build()

    private companion object {
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}
