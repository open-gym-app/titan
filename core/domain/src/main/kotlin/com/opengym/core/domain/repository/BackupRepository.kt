package com.opengym.core.domain.repository

import com.opengym.core.domain.backup.model.BackupRow
import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.ExportDocument

/**
 * The database as rows, for the one consumer that wants it that way: backup.
 *
 * Every other repository in this package is shaped for a screen — it folds tables together,
 * derives what no column holds and hands back domain models. This one is deliberately the
 * opposite. An export is the only copy of the user's data, so it has to carry what is *stored*,
 * table by table and column by column, and it has to keep carrying it when a domain model is
 * refactored. That is why [BackupRow] mirrors the schema rather than the domain, and why this
 * interface talks about tables and pages instead of sessions and sets.
 *
 * `:core:domain` still names no Room symbol. What the data layer owes is below; how it does it
 * — DAOs, a transaction, the order the statements run in — is its own business.
 *
 * ### Reading is paged, and that is the point
 *
 * [readPage] exists so `ExportUseCase` never holds more than one page of one table. At the
 * scale `LargeHistoryBenchmarkTest` targets that is the difference between a few hundred rows
 * in memory and fifty thousand `workout_set`s plus the JSON they encode into, on a phone that
 * is also drawing a progress bar.
 *
 * ### Writing is one call, and that is also the point
 *
 * [replaceAll] takes the whole document because the wipe and every insert have to be one
 * transaction: an import that failed halfway would otherwise leave a device with a wiped
 * database and no backup left to restore from. Nothing here offers a wipe on its own, or an
 * insert on its own, because neither is a thing a caller should be able to do.
 */
interface BackupRepository {

    /**
     * How many rows each table holds, for every table in [BackupTable.entries].
     *
     * Read up front so an export can report determinate progress — see `ExportProgress`. Every
     * table is present in the map even when it is empty, so a caller may index it without
     * defaulting.
     */
    suspend fun rowCounts(): Map<BackupTable, Long>

    /**
     * At most [limit] rows of [table], skipping the first [offset], ordered by primary key.
     *
     * The order is stable and is the same one [replaceAll] writes in, which is what makes an
     * export/import round trip row-for-row identical rather than merely set-equal.
     *
     * Paged by offset rather than by keyset: three tables have composite primary keys and one
     * has no id at all, so a keyset cursor would need a shape per table to save a scan that
     * costs, at the target scale, a couple of million index steps across the whole export.
     */
    suspend fun readPage(
        table: BackupTable,
        offset: Int,
        limit: Int,
    ): List<BackupRow>

    /**
     * Empties every table and inserts [document]'s rows in their place, as one transaction.
     *
     * Replace, not merge, and not upsert: afterwards the database holds exactly what the
     * document holds, and a row that was there before and is absent from the file is gone. See
     * docs/export-schema.md for why that is the semantics.
     *
     * Rows go in in [BackupTable.entries] order, which is dependency order, so foreign keys
     * hold at every point rather than only at the end.
     *
     * @param onTableWritten called at least once per table, with the running total of rows
     *   written across the whole document — a table inserted in several batches reports after
     *   each one, and an empty table still reports once, so a caller may name every table but
     *   must not count emissions. It runs *inside* the transaction, so a caller must not block
     *   in it — `ImportUseCase` sends into a channel, which is safe from any context.
     * @throws com.opengym.core.domain.backup.BackupConstraintException if the database refuses
     *   a row. `BackupValidator` has already checked everything the schema can express, so this
     *   is the backstop for a constraint it does not model; the transaction is rolled back
     *   before it is thrown, leaving the database exactly as it was.
     */
    suspend fun replaceAll(
        document: ExportDocument,
        onTableWritten: suspend (BackupTable, Long) -> Unit,
    )
}
