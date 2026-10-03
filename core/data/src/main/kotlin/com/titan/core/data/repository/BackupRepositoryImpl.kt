package com.titan.core.data.repository

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.titan.core.data.db.DatabaseSeed
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.di.IoDispatcher
import com.titan.core.data.mapper.toEntity
import com.titan.core.data.mapper.toRow
import com.titan.core.domain.backup.BackupConstraintException
import com.titan.core.domain.backup.model.BackupRow
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The database as rows: paged reads for an export, and one transactional replace for an import.
 *
 * ### The wipe is inside the transaction, and `clearAllTables` therefore is not used
 *
 * `LocalDataRepositoryImpl.deleteAll` — the settings screen's "delete all data" — calls
 * `RoomDatabase.clearAllTables`, which is right for it: it reclaims the space with a `VACUUM`
 * and replays the seed afterwards so the device is left with a working exercise library.
 * Neither is usable here.
 *
 * `VACUUM` cannot run inside a transaction, and the wipe has to be inside one: an import that
 * cleared the tables, committed, and *then* failed to insert would leave a device with no data
 * and a file it has already been told is bad. The whole point of
 * [BackupRepository.replaceAll]'s signature — document in, no separate wipe — is that there is
 * no such state. The seed replay is wrong for a different reason: the file carries the whole
 * exercise catalogue, so replaying the seed first would leave every seeded row the *file* does
 * not contain still in the table, and "replace" would quietly become "replace, plus whatever
 * this build ships with".
 *
 * What is left is a `DELETE FROM` per table, which is what the five backup DAOs' delete
 * methods are. They cannot drift from the schema in the way the issue behind this warns about,
 * because they are `@Query` statements Room parses against the entities: a table renamed and
 * not renamed here fails the build, and a table added and not added here fails
 * `ExportSchemaTest`, which reads the exported schema and asserts [BackupTable] lists exactly
 * what `TitanDatabase` declares.
 *
 * ### One thing is added to the file: the pace tags its catalogue predates
 *
 * A backup taken before the `SPEED` and `SWIM` tags were seeded carries a catalogue without
 * them, and restoring it as it stands would bring back every ride's best as a pace per kilometre
 * on a device that had been reading it as a speed. So the restore ends with
 * [DatabaseSeed.backfillPaceTags], inside the same transaction. That is not the seed replay the
 * section above rules out: it adds no exercise, only links between the file's own seeded
 * exercises and the tags this build reads them by, and it writes nothing at all over a file with
 * none of those exercises — or one that already has the links.
 *
 * ### Order, not `defer_foreign_keys`
 *
 * Foreign keys stay enforced for the whole restore. Deletes run in [BackupTable.DELETE_ORDER] —
 * the declaration order reversed, so every child is emptied before its parent — and inserts run
 * in [BackupTable.entries] order, which is dependency order. Referential integrity therefore
 * holds at every point of the transaction rather than only at its commit, which means a bug in
 * the ordering surfaces as a failed test rather than as a pragma quietly covering for it.
 *
 * ### Cancellation is safe, and is left alone
 *
 * No [kotlinx.coroutines.NonCancellable] here, unlike `deleteAll`. Everything destructive is
 * inside the one transaction, so a cancelled import rolls back and the database is exactly as
 * it was — there is no half-applied state to protect against. The two steps that *do* happen
 * outside it, clearing the live session and restoring the settings, belong to `ImportUseCase`
 * and are documented there.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val database: TitanDatabase,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BackupRepository {

    /*
     * Reached through the database rather than injected beside it, as `LocalDataRepositoryImpl`
     * does: the two would always be the same database, and a constructor that takes both
     * invites the day they are not.
     */
    private val dao get() = database.backupDao()

    /**
     * A `COUNT(*)` per table, run as raw SQL rather than as nineteen more DAO methods.
     *
     * The table name is interpolated, which is safe here and only here: it comes from
     * [BackupTable], a closed enum of SQLite identifiers with no user input anywhere near it.
     * The alternative is nineteen `@Query("SELECT COUNT(*) FROM …")` declarations that no
     * screen reads, spread across five interfaces already at their method budget, to produce
     * one integer each.
     */
    override suspend fun rowCounts(): Map<BackupTable, Long> =
        withContext(ioDispatcher) {
            BackupTable.entries.associateWith { table ->
                database.query("SELECT COUNT(*) FROM `${table.tableName}`", emptyArray()).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
            }
        }

    override suspend fun readPage(
        table: BackupTable,
        offset: Int,
        limit: Int,
    ): List<BackupRow> =
        withContext(ioDispatcher) {
            when (table) {
                BackupTable.MUSCLE_GROUP -> dao.readMuscleGroups(limit, offset).map { it.toRow() }

                BackupTable.TAG -> dao.readTags(limit, offset).map { it.toRow() }

                BackupTable.EXERCISE -> dao.readExercises(limit, offset).map { it.toRow() }

                BackupTable.EXERCISE_MUSCLE_GROUP ->
                    dao.readExerciseMuscleGroups(limit, offset).map { it.toRow() }

                BackupTable.EXERCISE_TAG -> dao.readExerciseTags(limit, offset).map { it.toRow() }

                BackupTable.WORKOUT_TEMPLATE -> dao.readTemplates(limit, offset).map { it.toRow() }

                BackupTable.TEMPLATE_EXERCISE -> dao.readTemplateExercises(limit, offset).map { it.toRow() }

                BackupTable.TEMPLATE_SET -> dao.readTemplateSets(limit, offset).map { it.toRow() }

                BackupTable.WORKOUT_SESSION -> dao.readSessions(limit, offset).map { it.toRow() }

                BackupTable.SESSION_EXERCISE -> dao.readSessionExercises(limit, offset).map { it.toRow() }

                BackupTable.WORKOUT_SET -> dao.readSets(limit, offset).map { it.toRow() }

                BackupTable.PERSONAL_RECORD -> dao.readPersonalRecords(limit, offset).map { it.toRow() }

                BackupTable.BODY_MEASUREMENT -> dao.readBodyMeasurements(limit, offset).map { it.toRow() }

                BackupTable.SESSION_XP_CLAIM -> dao.readXpClaims(limit, offset).map { it.toRow() }

                BackupTable.SESSION_LOOT_CLAIM -> dao.readLootClaims(limit, offset).map { it.toRow() }

                BackupTable.USER_PROGRESS -> dao.readUserProgress(limit, offset).map { it.toRow() }

                BackupTable.ACHIEVEMENT -> dao.readAchievements(limit, offset).map { it.toRow() }

                BackupTable.QUEST_COMPLETION -> dao.readQuestCompletions(limit, offset).map { it.toRow() }

                BackupTable.COLLECTED_ITEM -> dao.readCollectedItems(limit, offset).map { it.toRow() }
            }
        }

    override suspend fun replaceAll(
        document: ExportDocument,
        onTableWritten: suspend (BackupTable, Long) -> Unit,
    ) {
        withContext(ioDispatcher) {
            try {
                database.withTransaction {
                    deleteEverything()
                    insertEverything(document, Insertion(onTableWritten))
                    DatabaseSeed.backfillPaceTags(database.openHelper.writableDatabase, context.assets)
                }
            } catch (failure: SQLiteException) {
                // The transaction has already rolled back by the time this is reached, so the
                // database is exactly as it was. Wrapped rather than propagated because
                // `:core:domain` has no SQLite on its classpath and could otherwise only catch
                // this as `Exception` — which would report a programming error to the user as a
                // corrupt backup file. `BackupValidator` refuses everything the schema can
                // express before the wipe, so this is the backstop for a constraint it does not
                // model, not the mechanism.
                throw BackupConstraintException(failure.message.orEmpty(), failure)
            }
        }
    }

    /** Empties every table, children before parents, so no delete trips a `RESTRICT`. */
    private suspend fun deleteEverything() {
        for (table in BackupTable.DELETE_ORDER) {
            when (table) {
                BackupTable.MUSCLE_GROUP -> dao.deleteMuscleGroups()
                BackupTable.TAG -> dao.deleteTags()
                BackupTable.EXERCISE -> dao.deleteExercises()
                BackupTable.EXERCISE_MUSCLE_GROUP -> dao.deleteExerciseMuscleGroups()
                BackupTable.EXERCISE_TAG -> dao.deleteExerciseTags()
                BackupTable.WORKOUT_TEMPLATE -> dao.deleteTemplates()
                BackupTable.TEMPLATE_EXERCISE -> dao.deleteTemplateExercises()
                BackupTable.TEMPLATE_SET -> dao.deleteTemplateSets()
                BackupTable.WORKOUT_SESSION -> dao.deleteSessions()
                BackupTable.SESSION_EXERCISE -> dao.deleteSessionExercises()
                BackupTable.WORKOUT_SET -> dao.deleteSets()
                BackupTable.PERSONAL_RECORD -> dao.deletePersonalRecords()
                BackupTable.BODY_MEASUREMENT -> dao.deleteBodyMeasurements()
                BackupTable.SESSION_XP_CLAIM -> dao.deleteXpClaims()
                BackupTable.SESSION_LOOT_CLAIM -> dao.deleteLootClaims()
                BackupTable.USER_PROGRESS -> dao.deleteUserProgress()
                BackupTable.ACHIEVEMENT -> dao.deleteAchievements()
                BackupTable.QUEST_COMPLETION -> dao.deleteQuestCompletions()
                BackupTable.COLLECTED_ITEM -> dao.deleteCollectedItems()
            }
        }
    }

    /** Inserts every table in dependency order, reporting the running total as it goes. */
    private suspend fun insertEverything(
        document: ExportDocument,
        into: Insertion,
    ) {
        into.table(BackupTable.MUSCLE_GROUP, document.muscleGroup, dao::insertMuscleGroups) { it.toEntity() }
        into.table(BackupTable.TAG, document.tag, dao::insertTags) { it.toEntity() }
        into.table(BackupTable.EXERCISE, document.exercise, dao::insertExercises) { it.toEntity() }
        into.table(
            BackupTable.EXERCISE_MUSCLE_GROUP,
            document.exerciseMuscleGroup,
            dao::insertExerciseMuscleGroups,
        ) { it.toEntity() }
        into.table(BackupTable.EXERCISE_TAG, document.exerciseTag, dao::insertExerciseTags) { it.toEntity() }
        into.table(BackupTable.WORKOUT_TEMPLATE, document.workoutTemplate, dao::insertTemplates) { it.toEntity() }
        into.table(
            BackupTable.TEMPLATE_EXERCISE,
            document.templateExercise,
            dao::insertTemplateExercises,
        ) { it.toEntity() }
        into.table(BackupTable.TEMPLATE_SET, document.templateSet, dao::insertTemplateSets) { it.toEntity() }
        into.table(BackupTable.WORKOUT_SESSION, document.workoutSession, dao::insertSessions) { it.toEntity() }
        into.table(
            BackupTable.SESSION_EXERCISE,
            document.sessionExercise,
            dao::insertSessionExercises,
        ) { it.toEntity() }
        into.table(BackupTable.WORKOUT_SET, document.workoutSet, dao::insertSets) { it.toEntity() }
        into.table(
            BackupTable.PERSONAL_RECORD,
            document.personalRecord,
            dao::insertPersonalRecords,
        ) { it.toEntity() }
        into.table(
            BackupTable.BODY_MEASUREMENT,
            document.bodyMeasurement,
            dao::insertBodyMeasurements,
        ) { it.toEntity() }
        into.table(BackupTable.SESSION_XP_CLAIM, document.sessionXpClaim, dao::insertXpClaims) { it.toEntity() }
        into.table(
            BackupTable.SESSION_LOOT_CLAIM,
            document.sessionLootClaim,
            dao::insertLootClaims,
        ) { it.toEntity() }
        into.table(BackupTable.USER_PROGRESS, document.userProgress, dao::insertUserProgress) { it.toEntity() }
        into.table(BackupTable.ACHIEVEMENT, document.achievement, dao::insertAchievements) { it.toEntity() }
        into.table(
            BackupTable.QUEST_COMPLETION,
            document.questCompletion,
            dao::insertQuestCompletions,
        ) { it.toEntity() }
        into.table(
            BackupTable.COLLECTED_ITEM,
            document.collectedItem,
            dao::insertCollectedItems,
        ) { it.toEntity() }
    }

    /**
     * The running insert, and the only thing that knows how many rows have gone in.
     *
     * A tiny class rather than a `var` threaded through nineteen calls: the total has to survive
     * every table, and a helper that took it and returned it would put the same local on both
     * ends of nineteen assignments, where dropping one is a silent progress bug.
     */
    private class Insertion(private val report: suspend (BackupTable, Long) -> Unit) {

        private var written = 0L

        /**
         * Inserts [rows] into [table] in chunks, reporting after each one — so a 50,000-row
         * table moves the progress bar a hundred times rather than once, and SQLite is handed a
         * batch it can bind rather than one statement with fifty thousand rows of arguments.
         *
         * An empty table still reports once, so a caller drawing "restoring <table>…" names
         * every table rather than skipping the ones that happen to be empty.
         */
        suspend fun <R, E> table(
            table: BackupTable,
            rows: List<R>,
            insert: suspend (List<E>) -> Unit,
            toEntity: (R) -> E,
        ) {
            if (rows.isEmpty()) {
                report(table, written)
                return
            }
            for (chunk in rows.chunked(INSERT_CHUNK)) {
                insert(chunk.map(toEntity))
                written += chunk.size
                report(table, written)
            }
        }
    }

    private companion object {

        /**
         * Rows per `@Insert` call.
         *
         * Room binds a batch in one prepared statement, so the figure trades statement size
         * against round trips; five hundred keeps both small and matches
         * `ExportUseCase.DEFAULT_PAGE_SIZE`, so a document exported in pages of five hundred is
         * restored in inserts of five hundred. Nothing depends on it — the result is identical
         * at any chunk size, which the round-trip test relies on by not setting it.
         */
        const val INSERT_CHUNK = 500
    }
}
