package com.titan.core.data.worker

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.titan.core.data.di.IoDispatcher
import com.titan.core.data.importer.HistoryImportNotifications
import com.titan.core.data.importer.HistoryImportWatchers
import com.titan.core.data.importer.HistoryImportWork
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryParsers
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.usecase.ImportHistoryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An import from another app, run where leaving the screen cannot cancel it (#252).
 *
 * ### Why a worker, and why a foreground one
 *
 * [ImportHistoryUseCase] writes the history in one transaction and rolls it back if its
 * collector is cancelled. A ViewModel's scope ends with the screen, and a large history takes
 * minutes, so the screen hands the import to this worker and observes it through
 * `WorkManagerHistoryImportRunner`. The worker promotes itself to a foreground service with a
 * progress notification as its first act: a background job may be stopped after ten minutes,
 * and a stopped import is one that wrote nothing.
 *
 * ### An interrupted run is not run again
 *
 * WorkManager re-runs work whose process died, and a re-run would be an import the user did not
 * start, at a moment they did not choose, holding every database write behind it — a set being
 * ticked off included. So a run that is not the first attempt ([runAttemptCount] above zero)
 * does nothing and fails as *interrupted*, which the screen turns into "the import did not
 * finish" and an offer to run it again. An attempt that died before its commit wrote nothing —
 * its transaction went with its process — and one that died storing photos left the history in
 * and some photos out; either way, running the same file again adds only what is missing.
 *
 * ### What it reports, and how often
 *
 * The phase goes out as WorkManager progress, which the screen reads, and onto the notification.
 * Both are writes — progress is a row in WorkManager's own database — so a phase is published
 * only when it changes or its percentage does: at most a hundred times a phase, however many
 * workouts there are. The use case drops the updates a slow collector does not take, so the
 * counts this publishes can skip ahead and never go back.
 *
 * The report or the refusal is the work's output, which WorkManager keeps after the worker has
 * gone; see [HistoryImportWork] for the encoding.
 *
 * @property importHistory the engine; it parses, plans, writes and replays.
 * @property parsers which parser reads the source the user picked.
 * @property userPreferencesRepository the unit system a figure with no unit is read in.
 * @property notifications the progress notification and the line saying it ended.
 * @property watchers whether that line is needed: with the import screen open, the screen says it.
 * @property ioDispatcher where the import is collected. The parse blocks, and `CoroutineWorker`
 *   runs on WorkManager's own executor rather than a dispatcher this module chose.
 */
@HiltWorker
class HistoryImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val importHistory: ImportHistoryUseCase,
    private val parsers: HistoryParsers,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val notifications: HistoryImportNotifications,
    private val watchers: HistoryImportWatchers,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val request = HistoryImportWork.requestOf(inputData)
            ?: return Result.failure(
                HistoryImportWork.failureOutput(HistoryImportError.Unreadable("no document to import")),
            )
        if (runAttemptCount > 0) return Result.failure(HistoryImportWork.interruptedOutput())

        STARTED_HERE.add(id)
        val outcome = withContext(ioDispatcher) { runImport(request) }
        announce(outcome)
        return when (outcome) {
            is HistoryImportProgress.Complete -> Result.success(HistoryImportWork.reportOutput(outcome.report))

            is HistoryImportProgress.Failed -> Result.failure(HistoryImportWork.failureOutput(outcome.error))

            // `runImport` returns only the two terminals.
            else -> Result.failure(HistoryImportWork.failureOutput(HistoryImportError.StorageFailure("no outcome")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(HistoryImportProgress.Reading, cancelIntent())

    /** Collects the import, publishing each new phase, and answers with the terminal emission. */
    private suspend fun runImport(request: HistoryImportRequest): HistoryImportProgress {
        publish(HistoryImportProgress.Reading)
        val context = ImportContext(
            zone = ZoneId.systemDefault(),
            unitSystem = userPreferencesRepository.observe().first().unitSystem,
        )
        var terminal: HistoryImportProgress =
            HistoryImportProgress.Failed(HistoryImportError.StorageFailure("the import ended without an outcome"))
        var published: Published? = Published.of(HistoryImportProgress.Reading)
        importHistory(documentAt(request.document), parsers.parserFor(request.source), context).collect { progress ->
            when (progress) {
                is HistoryImportProgress.Complete, is HistoryImportProgress.Failed -> terminal = progress

                else -> {
                    val next = Published.of(progress)
                    if (next != published) {
                        published = next
                        publish(progress)
                    }
                }
            }
        }
        return terminal
    }

    /**
     * Writes [progress] where the screen and the notification read it.
     *
     * The notification is updated through [setForeground], which is also what makes the worker a
     * foreground service the first time. Starting one can be refused — from the background on
     * Android 12 and later — and a refusal costs the notification, never the import, which runs
     * on regardless.
     */
    private suspend fun publish(progress: HistoryImportProgress) {
        setProgress(HistoryImportWork.progressData(progress))
        try {
            setForeground(notifications.foregroundInfo(progress, cancelIntent()))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: IllegalStateException) {
            // `ForegroundServiceStartNotAllowedException` is one. See above.
        }
    }

    /**
     * Says how the import ended, unless the import screen is open to say it itself. Not "unless
     * the app is in the foreground": the running screen tells the user they can leave it, and one
     * who went back to their workouts would otherwise never hear that it ended.
     */
    private fun announce(outcome: HistoryImportProgress) {
        if (watchers.anyone) return
        notifications.showFinished(
            succeeded = outcome is HistoryImportProgress.Complete,
            workoutsImported = (outcome as? HistoryImportProgress.Complete)?.report?.workoutsImported ?: 0,
        )
    }

    /** The notification's stop button: cancels this work, which rolls back what is uncommitted. */
    private fun cancelIntent() = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    /**
     * The picked document as a re-openable source.
     *
     * A permission the provider has withdrawn surfaces as a `SecurityException`, which the
     * engine would otherwise report as a malformed file. It is an unreadable one — "pick it
     * again" is the sentence that helps — so it is turned into the `IOException` the engine
     * reads as exactly that.
     */
    private fun documentAt(document: String): ImportSource {
        val uri = document.toUri()
        return ImportSource {
            try {
                applicationContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(document)
            } catch (failure: SecurityException) {
                throw IOException("no permission to read $document", failure)
            }
        }
    }

    /** What was last published: the phase and its whole percentage. */
    private data class Published(
        val phase: Class<*>,
        val percent: Int,
    ) {
        companion object {
            fun of(progress: HistoryImportProgress): Published =
                when (progress) {
                    is HistoryImportProgress.Writing -> Published(
                        progress.javaClass,
                        percent(progress.workoutsWritten, progress.workoutsTotal),
                    )

                    is HistoryImportProgress.StoringPhotos -> Published(
                        progress.javaClass,
                        percent(progress.photosStored, progress.photosTotal),
                    )

                    else -> Published(progress.javaClass, 0)
                }

            private fun percent(
                done: Int,
                total: Int,
            ): Int = if (total <= 0) 0 else (done.toLong() * PERCENT / total).toInt()

            private const val PERCENT = 100
        }
    }

    companion object {

        /**
         * The work whose first attempt began in this process. Read by the runner to tell a
         * `RUNNING` record this process is behind from one left by a process that died — see
         * `HistoryImportWork.runOf`.
         *
         * Never emptied, rather than counted down when [doWork] returns: WorkManager records the
         * outcome only after that, and a record read in between — still `RUNNING`, its phase
         * published, no worker left — would otherwise read as interrupted for the moment before
         * the report arrives. One id per import started, for the life of the process.
         */
        private val STARTED_HERE: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

        /** Whether the import [id] names started in this process, so its record is this process's. */
        fun startedHere(id: UUID): Boolean = id in STARTED_HERE
    }
}
