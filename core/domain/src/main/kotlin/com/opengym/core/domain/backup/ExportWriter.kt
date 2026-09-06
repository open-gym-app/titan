package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.BackupRow
import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.PreferencesPayload

/**
 * Writes an [ExportDocument] into an [ExportSink] a page of rows at a time, without ever
 * holding the whole thing.
 *
 * ### Why the document is not simply serialized
 *
 * `Json.encodeToString(document)` needs the document, and a document needs every row of every
 * table in memory at once — 50,000 `workout_set` rows at the scale
 * `LargeHistoryBenchmarkTest` targets, and that is before the string it encodes into. This
 * writes the same bytes incrementally instead: the envelope, then each table's array opened,
 * filled a page at a time, and closed. Peak memory is one page of rows plus the text of that
 * page, whatever the history's size. `ExportFormatTest` asserts the output is byte-identical to
 * what serializing the equivalent document would have produced, which is what lets the importer
 * decode with the ordinary decoder and lets docs/export-schema.md describe one format rather
 * than two.
 *
 * ### The protocol
 *
 * [writeHeader] once, then [writeRows] for tables in [BackupTable.entries] order, then
 * [finish]. A table may be filled by any number of [writeRows] calls — that is the point — but
 * the tables themselves must not go backwards, because a table already closed cannot be
 * reopened without rewriting bytes the sink has already been handed. A table that is never
 * mentioned is written as an empty array, so the document always carries all nineteen keys
 * whether or not the caller bothered to ask about an empty table.
 *
 * Misuse throws [IllegalStateException] rather than producing a document that parses into
 * something other than what was exported. This is a class with a lifecycle over a stream, and
 * the ways to hold it wrong are exactly the ways to write a file the user cannot restore.
 *
 * Not thread-safe, and deliberately so: it is a cursor over one document, driven by one
 * coroutine — `ExportUseCase`'s flow.
 */
class ExportWriter(private val sink: ExportSink) {

    /** The table whose array is currently open, or null before the first one and after [finish]. */
    private var openTable: BackupTable? = null

    /** How many rows have gone into [openTable], so the writer knows whether a row needs a comma before it. */
    private var rowsInOpenTable = 0L

    /** The first table not yet written. Anything before it has been opened and closed already. */
    private var nextTableIndex = 0

    private var headerWritten = false
    private var finished = false

    /**
     * Opens the document and writes its envelope: the schema version, [exportedAt],
     * [appVersion] and [preferences].
     *
     * @throws IllegalStateException if called twice, or after [finish].
     */
    suspend fun writeHeader(
        exportedAt: Long,
        appVersion: String,
        preferences: PreferencesPayload,
    ) {
        check(!headerWritten) { "the header has already been written" }
        check(!finished) { "the document is finished" }
        headerWritten = true
        sink.write(ExportFormat.encodeEnvelope(exportedAt, appVersion, preferences))
    }

    /**
     * Appends [rows] to [table]'s array, opening it — and closing whatever table was open
     * before — if this is the first page of it.
     *
     * An empty [rows] still opens the table, which is how a caller says "this table is done and
     * held nothing" without a second method.
     *
     * @throws IllegalStateException if the header has not been written, the document is
     *   finished, or [table] comes before one already written.
     */
    suspend fun writeRows(
        table: BackupTable,
        rows: List<BackupRow>,
    ) {
        check(headerWritten) { "the header must be written before any rows" }
        check(!finished) { "the document is finished" }
        if (openTable != table) openArrayFor(table)
        if (rows.isEmpty()) return

        val chunk = StringBuilder()
        var written = rowsInOpenTable
        for (row in rows) {
            if (written > 0) chunk.append(ROW_SEPARATOR)
            chunk.append(ExportFormat.encodeRow(row))
            written++
        }
        rowsInOpenTable = written
        sink.write(chunk.toString())
    }

    /**
     * Closes the open table, writes empty arrays for every table never mentioned, and closes
     * the document.
     *
     * Idempotent is *not* what this is: calling it twice would append a second `}`. It throws
     * instead, for the reason the rest of the protocol does.
     *
     * @throws IllegalStateException if the header has not been written, or the document is
     *   already finished.
     */
    suspend fun finish() {
        check(headerWritten) { "the header must be written before the document is finished" }
        check(!finished) { "the document is already finished" }
        finished = true

        val tail = StringBuilder()
        if (openTable != null) tail.append(ARRAY_CLOSE)
        appendEmptyTablesUpTo(tail, BackupTable.entries.size)
        tail.append(OBJECT_CLOSE)
        openTable = null
        sink.write(tail.toString())
    }

    /** Closes the previous array, fills in every table skipped over, and opens [table]'s. */
    private suspend fun openArrayFor(table: BackupTable) {
        val index = BackupTable.entries.indexOf(table)
        check(index >= nextTableIndex) {
            "tables must be written in BackupTable order; ${table.tableName} comes before " +
                BackupTable.entries[nextTableIndex - 1].tableName
        }

        val prelude = StringBuilder()
        if (openTable != null) prelude.append(ARRAY_CLOSE)
        appendEmptyTablesUpTo(prelude, index)
        prelude.append(KEY_SEPARATOR).append(quoted(table.tableName)).append(NAME_SEPARATOR).append(ARRAY_OPEN)

        openTable = table
        rowsInOpenTable = 0
        nextTableIndex = index + 1
        sink.write(prelude.toString())
    }

    /** Writes `"table":[]` for every table between the last one opened and [exclusiveEnd]. */
    private fun appendEmptyTablesUpTo(
        target: StringBuilder,
        exclusiveEnd: Int,
    ) {
        for (index in nextTableIndex until exclusiveEnd) {
            target.append(KEY_SEPARATOR)
                .append(quoted(BackupTable.entries[index].tableName))
                .append(NAME_SEPARATOR)
                .append(ARRAY_OPEN)
                .append(ARRAY_CLOSE)
        }
        nextTableIndex = maxOf(nextTableIndex, exclusiveEnd)
    }

    private companion object {

        /*
         * The document's punctuation, named rather than spelled inline. Table names are the
         * only strings this class interpolates and they come from a closed enum of SQLite
         * identifiers — no quote, backslash or control character among them — so quoting is a
         * pair of `"` rather than an escaper. `ExportFormatTest`'s byte-for-byte comparison
         * against kotlinx's own encoder is what holds that true.
         */
        const val KEY_SEPARATOR = ","
        const val NAME_SEPARATOR = ":"
        const val ROW_SEPARATOR = ","
        const val ARRAY_OPEN = "["
        const val ARRAY_CLOSE = "]"
        const val OBJECT_CLOSE = "}"

        fun quoted(name: String) = "\"$name\""
    }
}
