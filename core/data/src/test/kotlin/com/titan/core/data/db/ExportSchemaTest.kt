package com.titan.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.test.platform.app.InstrumentationRegistry
import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.data.repository.BackupRepositoryImpl
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.UserProgressRow
import com.titan.core.domain.usecase.ExportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export format against the schema it claims to mirror — read out of the committed
 * `1.json`, not out of the code that writes the file.
 *
 * ### Why this is the test the format most needs
 *
 * Every other backup test asks whether the code agrees with itself: the round trip compares an
 * export with its own import, the format tests compare a streamed document with an encoded one.
 * None of them can see a **column that was never exported in the first place**. Add
 * `workout_set.tempo` tomorrow, forget `WorkoutSetRow`, and the round trip still passes — the
 * column is missing from both ends — while every backup taken afterwards quietly loses it.
 *
 * So this reads the other side: `core/data/schemas/…/1.json`, the file KSP writes and
 * docs/PERSISTENCE.md calls the only record of what shipped, wired in as a test asset. A table
 * or a column that exists there and not in the format fails here, at the commit that added it,
 * rather than in a support thread a year later.
 */
class ExportSchemaTest : RoomTestBase() {

    private val backup by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }

    private val export by lazy {
        ExportUseCase(
            backupRepository = backup,
            userPreferencesRepository = InMemoryUserPreferencesRepository(),
            clock = { 0 },
        )
    }

    /**
     * The exclusion list is held to account too: it may only name tables the schema has and the
     * format leaves out, so an entry cannot outlive its table or quietly hide one that is exported.
     */
    @Test
    fun `every table left out of the format is a real table, left out on purpose`() {
        assertTrue(declaredTables().containsAll(NOT_EXPORTED))
        assertTrue(BackupTable.entries.none { it.tableName in NOT_EXPORTED })
    }

    @Test
    fun `the format lists exactly the tables the schema declares`() {
        assertEquals(schemaTables().toSortedSet(), BackupTable.entries.map { it.tableName }.toSortedSet())
    }

    /**
     * Column for column, table for table, and in the schema's own order — so a column added to
     * an entity and not to its row fails here, and one added in the wrong place is caught too.
     */
    @Test
    fun `every table's rows carry exactly that table's columns, in order`() =
        runBlocking {
            db.seedEveryTable()
            val document = Json.parseToJsonElement(exportToText()).jsonObject

            schemaColumns().forEach { (table, columns) ->
                val rows = document.getValue(table).jsonArray
                assertTrue("$table has no row in the fixture, so its columns are untested", rows.isNotEmpty())
                assertEquals("$table's columns drifted", columns, rows.first().jsonObject.keys.toList())
            }
        }

    /** The envelope's own keys, so nothing collides with a table name and nothing is missing. */
    @Test
    fun `the document's keys are the envelope's followed by the tables`() =
        runBlocking {
            db.seedEveryTable()

            val keys = Json.parseToJsonElement(exportToText()).jsonObject.keys.toList()

            assertEquals(ENVELOPE_KEYS + BackupTable.entries.map { it.tableName }, keys)
        }

    /**
     * The id the importer refuses a `user_progress` row for is the one the database refuses it
     * for too.
     *
     * The constant is duplicated — `UserProgressEntity.SINGLE_ROW_ID` in `:core:data`,
     * [UserProgressRow.SINGLE_ROW_ID] in `:core:domain`, because the validator has to refuse a
     * bad id in a module with no Room on its classpath. This is the only thing holding the two
     * together, so it checks both halves: that the constants agree, and that the table itself
     * still enforces it. The `CHECK` is applied by `singleRowProgressCallback` rather than by
     * Room, so it is *not* in the exported schema and cannot be read out of it — which is
     * exactly why it is asserted against the live database instead.
     */
    @Test
    fun `the single-row id the importer enforces is the one the database enforces`() =
        runBlocking {
            assertEquals(UserProgressEntity.SINGLE_ROW_ID, UserProgressRow.SINGLE_ROW_ID)

            val refused = runCatching {
                db.backupDao().insertUserProgress(listOf(UserProgressEntity(id = UserProgressRow.SINGLE_ROW_ID + 1)))
            }.exceptionOrNull()

            assertTrue(
                "user_progress accepted a second id, so the CHECK is not in place: $refused",
                refused is SQLiteConstraintException,
            )
        }

    /**
     * The document says which *format* it was written at, and the schema these tests read is the
     * live database's own.
     *
     * Two assertions rather than one, because the two versions are deliberately independent:
     * [ExportDocument.SCHEMA_VERSION] versions the JSON and the Room version versions the file,
     * and docs/export-schema.md is explicit that a migration ladder has nothing to say about a
     * document. What has to hold is that the export stamps its own constant — a build that
     * bumped the format and forgot the writer would ship files every reader refuses — and that
     * [schema] is reading the schema of the database under test rather than a stale asset.
     */
    @Test
    fun `the document carries the format version, and the schema read is the database's own`() =
        runBlocking {
            val databaseVersion = schema().getValue("database").jsonObject.getValue("version").jsonPrimitive.int
            assertEquals(databaseVersion, db.openHelper.readableDatabase.version)

            db.seedEveryTable()
            val document = Json.parseToJsonElement(exportToText()).jsonObject

            assertEquals(
                ExportDocument.SCHEMA_VERSION,
                document.getValue("schema_version").jsonPrimitive.int,
            )
        }

    private suspend fun exportToText(): String {
        val text = StringBuilder()
        export(ExportSink { text.append(it) }, "1.0-debug").toList()
        return text.toString()
    }

    /** Every table the committed schema declares, exported or not. */
    private fun declaredTables(): List<String> =
        schema().getValue("database").jsonObject
            .getValue("entities").jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }

    /** Every table the committed schema declares that the format carries. */
    private fun schemaTables(): List<String> = declaredTables().filterNot { it in NOT_EXPORTED }

    /** Table to its column names, in the order the schema records them. */
    private fun schemaColumns(): Map<String, List<String>> =
        schema().getValue("database").jsonObject
            .getValue("entities").jsonArray
            .associate { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                val columns = entity.jsonObject.getValue("fields").jsonArray
                    .map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
                table to columns
            }
            .filterKeys { it !in NOT_EXPORTED }

    /**
     * The committed schema for the current version, read from the test assets
     * `core/data/build.gradle.kts` points at `$projectDir/schemas`.
     */
    private fun schema() =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("${TitanDatabase::class.java.canonicalName}/${db.openHelper.readableDatabase.version}.json")
            .use { Json.parseToJsonElement(it.reader().readText()).jsonObject }

    private companion object {

        /** The document's non-table keys, in the order `ExportDocument` declares them. */
        val ENVELOPE_KEYS = listOf("schema_version", "exported_at", "app_version", "preferences")

        /**
         * Tables the format leaves out, deliberately. `progress_photo` names images that never
         * leave the phone — not in an export, not in Android's own backup — so its rows would be
         * names of files the file does not carry. See `ProgressPhoto`.
         */
        val NOT_EXPORTED = setOf("progress_photo")
    }
}
