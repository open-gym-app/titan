package com.opengym.core.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.opengym.core.data.backup.AutoBackupFiles
import com.opengym.core.data.di.IoDispatcher
import com.opengym.core.domain.backup.ExportSink
import com.opengym.core.domain.repository.AutoBackupRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.ExportUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The weekly automatic backup: one export, written to `backups/` and pruned to three.
 *
 * ### It is deliberately silent
 *
 * Nothing here posts a notification, on success or on failure, and it is not an omission. A
 * successful backup is not news — it is the thing the user asked for, happening — and a
 * background failure at three in the morning is not something they can act on when it
 * interrupts them. Both outcomes are recorded in [AutoBackupRepository] and read back by the
 * backup screen's toggle row, which is where somebody looks when they want to know.
 *
 * ### Failure, retry, and the one that is reported
 *
 * A run that throws asks WorkManager to retry it under its own default backoff, and only
 * the *last* attempt records a failure. That is the difference between "the storage was busy
 * once" and "this has not worked", and the row only earns attention if it says the second. The
 * partial file is deleted on the way out either way — see [AutoBackupFiles.scratchFor].
 *
 * ### The enabled check
 *
 * WorkManager outlives the app's own state: work survives reboots and updates, and a schedule
 * cancelled while the process was dead is cancelled *eventually*. Reading the flag first costs
 * one DataStore read and makes the toggle the authority on whether files are written, rather
 * than whatever the job scheduler happens to still hold.
 *
 * It is [AutoBackupRepository.read] rather than `observe().first()`, and the difference matters
 * at three in the morning: the flow falls back to `enabled = false` when the store cannot be
 * read, so a transient IO error would look exactly like the user having turned it off — the run
 * would report success, write nothing, ask for no retry and record no failure, and the backup
 * screen would keep showing the previous week's date. The strict read throws instead, and the
 * run joins the retry-then-report path below.
 *
 * @property exportBackup the same use case the manual export runs — one document format, one
 *   code path, and a file a restore reads back without knowing which produced it.
 * @property files where the backup goes, what it is called and how many are kept.
 * @property clock stamps the file's name and the recorded outcome, so both name the same run.
 * @property ioDispatcher everything below blocks: DataStore reads, the export's own encoding
 *   and every byte written. `CoroutineWorker` runs `doWork` on WorkManager's task executor
 *   rather than on a dispatcher this module chose, so the work is moved explicitly.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val exportBackup: ExportUseCase,
    private val autoBackupRepository: AutoBackupRepository,
    private val files: AutoBackupFiles,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result =
        withContext(ioDispatcher) {
            val startedAt = clock.nowMillis()
            runCatching {
                // Skipping is a success rather than a failure: there is nothing wrong, the user
                // simply turned it off, and failing would burn a retry and mark the row broken.
                // A read that *throws* is not that case — it stays inside `runCatching` and is
                // handled below, because an unreadable store is not an answer.
                if (autoBackupRepository.read().enabled) writeBackup(startedAt)
            }.fold(
                onSuccess = { Result.success() },
                // Broad, like the manual export's: every way a file write fails means the same
                // thing to a run that cannot ask anybody anything, and narrowing it would let a
                // storage failure this build has not thought of crash a background job.
                // Cancellation is not caught — `runCatching` would swallow it, so it is
                // re-thrown before anything is recorded.
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    giveUpOrRetry(startedAt)
                },
            )
        }

    /** Scratch file, stream, rename, prune, record. Throws on any storage failure. */
    private suspend fun writeBackup(startedAt: Long) {
        val destination = files.fileFor(startedAt)
        val scratch = files.scratchFor(destination)

        runCatching {
            scratch.bufferedWriter().use { writer ->
                exportBackup(ExportSink { chunk -> writer.write(chunk) }, appVersion()).collect()
            }
            if (!scratch.renameTo(destination)) throw IOException("could not move ${scratch.name} into place")
        }.onFailure {
            // Best-effort: a scratch file that will not delete keeps its `.tmp` name, which
            // retention ignores and no restore picker offers.
            scratch.delete()
        }.getOrThrow()

        files.prune(KEPT_BACKUPS)
        autoBackupRepository.recordSuccess(startedAt)
    }

    /**
     * Asks for another attempt, or records the failure and stops.
     *
     * [runAttemptCount] is zero on the first run, so the comparison counts *this* attempt.
     * Recording is itself a store write and can fail; a run that cannot even say it failed
     * still stops, because retrying it would only produce the same pair of failures. The one
     * throw that is not swallowed is cancellation, as everywhere else here — a job the OS took
     * back is not a job that failed.
     */
    private suspend fun giveUpOrRetry(startedAt: Long): Result {
        if (runAttemptCount + 1 < MAX_RUN_ATTEMPTS) return Result.retry()
        runCatching { autoBackupRepository.recordFailure(startedAt) }
            .onFailure { if (it is CancellationException) throw it }
        return Result.failure()
    }

    /**
     * The running build's version name, for the export envelope's diagnostics.
     *
     * Read from the package manager rather than from a `BuildConfig`, which is the one source
     * available here: `:core:data` is a library, so its own `BuildConfig.VERSION_NAME` would
     * stamp every automatic backup with the *library's* version. The manual export gets the
     * app's from `:app` through navigation; a worker has no screen to be handed it by.
     *
     * Nothing reads the field back and nothing branches on it, so a package manager that
     * refuses to answer costs an empty string rather than the backup.
     */
    @Suppress("DEPRECATION")
    private fun appVersion(): String =
        runCatching {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
        }.getOrNull().orEmpty()

    companion object {

        /**
         * How many automatic backups survive. Three weeks of them.
         *
         * Enough to notice that something has gone wrong and act on it — which is the only
         * thing an older copy buys, since a restore replaces everything and nobody wants last
         * month's history back — and few enough that a file which grows with every logged set
         * is not left multiplying on storage the user cannot browse to clear.
         */
        const val KEPT_BACKUPS: Int = 3

        /**
         * Attempts one week's run gets before it is reported as failed.
         *
         * Three, under the scheduler's exponential backoff: a storage volume that is busy or
         * briefly unmounted is usually not still that half an hour later, and a fourth attempt
         * against a genuinely full disk only delays the sentence on the screen that would let
         * the user fix it.
         */
        const val MAX_RUN_ATTEMPTS: Int = 3
    }
}
