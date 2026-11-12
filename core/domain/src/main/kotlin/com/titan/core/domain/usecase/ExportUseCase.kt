package com.titan.core.domain.usecase

import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.backup.ExportWriter
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ExportProgress
import com.titan.core.domain.backup.model.PreferencesPayload
import com.titan.core.domain.backup.toPayload
import com.titan.core.domain.repository.BackupRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first

/**
 * Writes the whole device — every table, plus the user's settings — into an [ExportSink] as one
 * versioned JSON document, reporting determinate progress as it goes.
 *
 * ### It never holds the history
 *
 * Reading is paged ([BackupRepository.readPage]) and writing is streamed ([ExportWriter]), so
 * at any moment this holds one page of one table and the text that page encodes into. That is
 * the difference between a few hundred rows and the fifty thousand `workout_set`s
 * `LargeHistoryBenchmarkTest` targets, on a phone that is also drawing the progress bar this
 * emits into. `ExportStreamingTest` asserts it, by exporting that fixture through a sink that
 * fails if it is ever handed more than a page's worth at once.
 *
 * The one thing that *is* held whole is the row count, taken up front so the bar is determinate
 * rather than a spinner — nineteen `COUNT(*)`s over indexed tables, which is cheap next to
 * reading the rows.
 *
 * ### It reads one state of the database, and nothing else replaces it meanwhile
 *
 * The counts and every page are read inside [BackupRepository.readConsistently], so a write
 * that lands mid-export cannot leave the file with a child row whose parent was paged before
 * the write — a file the next restore would refuse. The whole run, settings included, also
 * holds [BackupLock]: a restore or a delete-all replaces the rows *and then* the settings, and
 * an export between the two would carry the new history in the old units.
 *
 * A collector must therefore not write to the database while it collects, or wait on something
 * that does: the progress is sent from inside the read transaction, which that write would be
 * waiting for. Writing the chunks to a file and drawing a bar are what it is for.
 *
 * ### What it does not decide
 *
 * Where the bytes go, and what the file is called. Both belong to the screen (#38): this takes
 * a sink and an [appVersion] string and has no notion of a file, a content URI or a share
 * sheet. It does not delete or move anything either — a caller that wants a file to appear
 * atomically writes to a scratch sink and moves it once [ExportProgress.Complete] arrives,
 * because a flow that fails partway leaves a truncated document behind by construction.
 *
 * @property backupRepository the rows, table by table and page by page.
 * @property userPreferencesRepository the settings, read once — `observe().first()` returns
 *   immediately even on a fresh install, per that interface's emission contract.
 * @property clock stamps [ExportDocument.exportedAt]. Defaulted so a test that does not care
 *   when the document was written can leave it out; the graph passes its own.
 * @property pageSize how many rows are read and encoded at a time. Defaulted to
 *   [DEFAULT_PAGE_SIZE]; a test pins it small to prove the paging is real.
 * @property lock held for the whole run, against a restore or a delete-all. Defaulted to the
 *   process-wide [BackupLock.SHARED], which is the one the graph binds.
 */
class ExportUseCase(
    private val backupRepository: BackupRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val lock: BackupLock = BackupLock.SHARED,
) {

    init {
        require(pageSize > 0) { "the page size must be positive, was $pageSize" }
    }

    /**
     * Exports into [sink], emitting progress as each page lands.
     *
     * Every table produces at least one [ExportProgress.Writing], empty ones included, so a
     * screen naming the table it is on never skips one; the run ends with exactly one
     * [ExportProgress.Complete], which is the only emission that means the document is closed
     * and the file is whole.
     *
     * A `channelFlow` rather than a `flow`, for the reason `ImportUseCase` is one: the progress
     * is produced inside the read transaction, on the context that owns it, and a plain flow's
     * `emit` refuses a value from another context.
     *
     * @param sink where the document's chunks go, in order. See [ExportSink] for the contract.
     * @param appVersion stamped into the envelope for diagnostics — `BuildConfig.VERSION_NAME`
     *   at the call site. Nothing reads it back and nothing branches on it; it is there so a
     *   bug report with a file attached says which build wrote it.
     */
    operator fun invoke(
        sink: ExportSink,
        appVersion: String,
    ): Flow<ExportProgress> =
        channelFlow {
            lock.exclusively {
                // Read before the transaction opens: it is the settings store, not the
                // database, and the transaction is held against every writer in the app for as
                // long as it is open.
                val preferences = userPreferencesRepository.observe().first().toPayload()
                backupRepository.readConsistently { writeDocument(sink, appVersion, preferences) }
            }
        }

    /** The counts, the envelope and every table, page by page, into [sink]. */
    private suspend fun ProducerScope<ExportProgress>.writeDocument(
        sink: ExportSink,
        appVersion: String,
        preferences: PreferencesPayload,
    ) {
        val rowsTotal = backupRepository.rowCounts().values.sum()

        val writer = ExportWriter(sink)
        writer.writeHeader(
            exportedAt = clock.nowMillis(),
            appVersion = appVersion,
            preferences = preferences,
        )

        var rowsWritten = 0L
        for (table in BackupTable.entries) {
            var offset = 0
            do {
                val page = backupRepository.readPage(table = table, offset = offset, limit = pageSize)
                writer.writeRows(table, page)
                rowsWritten += page.size
                offset += page.size
                send(ExportProgress.Writing(table = table, rowsWritten = rowsWritten, rowsTotal = rowsTotal))
                // A short page is the last page. A full one may or may not be, so the loop asks
                // again and stops on the empty answer — one wasted query per table whose row
                // count happens to divide by the page size, against a count that would have to
                // be re-read per table to avoid it.
            } while (page.size == pageSize)
        }

        writer.finish()
        // Written, not counted. Inside one read transaction the two agree; the number of rows
        // that actually went into the file is still the one worth reporting.
        send(ExportProgress.Complete(rowsWritten = rowsWritten, rowsTotal = rowsWritten))
    }

    companion object {

        /**
         * Rows read, encoded and handed to the sink at a time.
         *
         * Five hundred `workout_set` rows are on the order of a hundred kilobytes of JSON —
         * small enough that peak memory is a rounding error next to the app itself, large
         * enough that a 50,000-set history is a hundred round trips to SQLite rather than
         * fifty thousand. Nothing depends on the exact figure; the document is identical
         * whatever it is, which is what `ExportFormatTest` pins by exporting the same data at
         * two different page sizes.
         */
        const val DEFAULT_PAGE_SIZE: Int = 500
    }
}
