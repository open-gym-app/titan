package com.titan.core.domain.usecase

import com.titan.core.domain.backup.model.BackupRow
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.repository.BackupRepository

/**
 * The database as a document, in memory.
 *
 * Paging is real — [readPage] slices [stored] exactly as SQLite's `LIMIT`/`OFFSET` would, and
 * every request is recorded — because what `ExportUseCase` owes is precisely that it never asks
 * for a whole table. A fake that returned everything on the first call would let a use case
 * that had stopped paging pass.
 *
 * @param stored what the database holds. [replaceAll] overwrites it, so a test can export,
 *   import and export again against one instance and see the round trip the app would.
 */
class FakeBackupRepository(var stored: ExportDocument) : BackupRepository {

    /** Every `(table, offset, limit)` [readPage] was asked for, in order. */
    val pageRequests = mutableListOf<PageRequest>()

    /** The document [replaceAll] was last handed, or null if it has never been called. */
    var replaced: ExportDocument? = null
        private set

    /** Thrown by [replaceAll] instead of writing, when set — the storage failure the use case must type. */
    var replaceFailure: Throwable? = null

    /** Appended to as the fake is driven, when a test needs the order of two repositories' calls. */
    var timeline: MutableList<String>? = null

    override suspend fun rowCounts(): Map<BackupTable, Long> =
        BackupTable.entries.associateWith { stored.rows(it).size.toLong() }

    override suspend fun readPage(
        table: BackupTable,
        offset: Int,
        limit: Int,
    ): List<BackupRow> {
        pageRequests += PageRequest(table, offset, limit)
        return stored.rows(table).drop(offset).take(limit)
    }

    override suspend fun replaceAll(
        document: ExportDocument,
        onTableWritten: suspend (BackupTable, Long) -> Unit,
    ) {
        timeline?.add(REPLACE_ALL)
        replaceFailure?.let { throw it }
        var written = 0L
        for (table in BackupTable.entries) {
            written += document.rows(table).size
            onTableWritten(table, written)
        }
        stored = document
        replaced = document
    }

    /** One call to [readPage], as it was made. */
    data class PageRequest(
        val table: BackupTable,
        val offset: Int,
        val limit: Int,
    )

    companion object {

        /** The [timeline] entry [replaceAll] writes, so a test can place it against the settings writes. */
        const val REPLACE_ALL = "replaceAll"
    }
}
