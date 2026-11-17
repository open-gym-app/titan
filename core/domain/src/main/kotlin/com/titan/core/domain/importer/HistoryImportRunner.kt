package com.titan.core.domain.importer

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Runs an import from another app somewhere that outlives the screen that asked for it.
 *
 * ### Why the screen does not collect the import itself
 *
 * [com.titan.core.domain.usecase.ImportHistoryUseCase] writes the whole history in one
 * transaction, and cancelling its collector rolls that transaction back. The backup restore can
 * be collected in a ViewModel because it takes seconds; an import of a large history takes
 * minutes, and a ViewModel's scope ends when the user leaves the screen — which would throw the
 * work away. So the screen hands the import to this port and observes it. `:core:data` runs it
 * in a foreground WorkManager worker with a progress notification.
 *
 * ### An import the system killed is offered again, not resumed
 *
 * A process killed part way discards the open transaction, so nothing of the history was
 * written. The implementation does not quietly run the import again on its own — it would hold
 * every database write behind it, a set being ticked off included, at a moment the user did not
 * choose — and reports [HistoryImportRun.Interrupted] instead, which the screen turns into "the
 * import did not finish" and a button that runs it again.
 */
interface HistoryImportRunner {

    /**
     * What the import is doing, or how the last one ended. Emits the current value on
     * subscription and every change after it; never completes and never fails.
     */
    fun observe(): Flow<HistoryImportRun>

    /**
     * Starts importing [request]. Does nothing while an import is already running: there is
     * one at a time, since a second would only wait behind the first one's locks.
     */
    suspend fun start(request: HistoryImportRequest)

    /**
     * Stops the running import, if there is one. Before the history is committed that writes
     * nothing; while the photos are being stored it only skips the photos not yet stored.
     */
    suspend fun cancel()

    /**
     * Forgets how the last import ended, so the screen opens on a fresh start rather than on a
     * report the user has already read. Does nothing while an import is running.
     */
    suspend fun dismiss()
}

/**
 * What to import: the app the user said the file came from, and the file.
 *
 * @property document where the file is, as the system document picker handed it back — a
 *   `content://` URI, kept as a string because this module has no Android types.
 *
 * Serializable because a screen holds a picked file in its saved state while it asks whether to
 * start, and a process death there should not send the user back through the picker.
 */
@Serializable
data class HistoryImportRequest(
    val source: HistoryImportSource,
    val document: String,
)

/** Where the import in the background is, as [HistoryImportRunner.observe] reports it. */
sealed interface HistoryImportRun {

    /** Nothing is running and nothing has ended that the user has not dismissed. */
    data object None : HistoryImportRun

    /**
     * The import is running.
     *
     * @property progress the latest phase: [HistoryImportProgress.Reading],
     *   [HistoryImportProgress.Writing] or [HistoryImportProgress.StoringPhotos], never a
     *   terminal one — those arrive as [Completed] and [Failed].
     */
    data class Running(
        val request: HistoryImportRequest,
        val progress: HistoryImportProgress,
    ) : HistoryImportRun

    /** The import finished, and [report] says what it wrote. */
    data class Completed(
        val request: HistoryImportRequest,
        val report: HistoryImportReport,
    ) : HistoryImportRun

    /** The import wrote nothing, for [error]'s reason. */
    data class Failed(
        val request: HistoryImportRequest,
        val error: HistoryImportError,
    ) : HistoryImportRun

    /**
     * The import was stopped by the system — the app's process killed, the work taken back —
     * before it finished. Nothing of the history was written; it can be run again.
     */
    data class Interrupted(val request: HistoryImportRequest) : HistoryImportRun

    /** The user stopped the import. */
    data class Cancelled(val request: HistoryImportRequest) : HistoryImportRun
}
