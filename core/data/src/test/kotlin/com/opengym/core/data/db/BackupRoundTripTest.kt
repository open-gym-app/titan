package com.opengym.core.data.db

import com.opengym.core.data.repository.BackupRepositoryImpl
import com.opengym.core.data.repository.SessionStateRepositoryImpl
import com.opengym.core.domain.backup.ExportFormat
import com.opengym.core.domain.backup.ExportSink
import com.opengym.core.domain.backup.ImportSource
import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.backup.model.ImportProgress
import com.opengym.core.domain.backup.model.WorkoutSetRow
import com.opengym.core.domain.backup.toPayload
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.usecase.ExportUseCase
import com.opengym.core.domain.usecase.ImportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export, wipe, import — over the real pipeline, over a real (Robolectric) SQLite, with every
 * one of the nineteen tables carrying rows.
 *
 * ### What only this test can see
 *
 * The domain tests pin the format and the rules with fakes standing in for the database, which
 * is the right place for them: they are about JSON and about `ImportError`. What they cannot
 * see is whether the *stored* schema agrees — whether a column reaches the row it belongs in,
 * whether the insert order actually satisfies the foreign keys SQLite is enforcing, whether a
 * `DELETE FROM` in the wrong order trips a `RESTRICT`, whether a rollback rolls back. Every
 * assertion below goes through `BackupRepositoryImpl` onto a database with
 * `PRAGMA foreign_keys = ON` and the `user_progress` `CHECK` in place, because those two are
 * callbacks rather than schema and a test database without them is one the constraints do not
 * hold in.
 */
class BackupRoundTripTest : RoomTestBase() {

    private val backup by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }
    private val preferences = InMemoryUserPreferencesRepository()
    private val draft = CountingInProgressSessionRepository()
    private val templateDraft = CountingTemplateDraftRepository()
    private val sessionState = SessionStateRepositoryImpl()

    private val export by lazy {
        ExportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            clock = { EXPORTED_AT },
        )
    }

    private val import by lazy {
        ImportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            inProgressSessionRepository = draft,
            templateDraftRepository = templateDraft,
            sessionStateRepository = sessionState,
        )
    }

    /** The acceptance criterion, whole: seed everything, export, wipe, import, compare row for row. */
    @Test
    fun `every table survives an export and an import, row for row`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()
            val document = exportToText()

            wipeEverything()
            val progress = import(source(document)).toList()

            assertEquals(ImportProgress.Complete(rowsWritten = rowCount(before)), progress.last())
            BackupTable.entries.forEach { table ->
                assertTrue("${table.tableName} was not seeded", before.getValue(table).isNotEmpty())
                assertEquals("${table.tableName} did not round-trip", before.getValue(table), db.dumpTable(table))
            }
        }

    /** Not just the counts: a column dropped on the way through has to fail this. */
    @Test
    fun `a row comes back with every column it went in with`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpTable(BackupTable.WORKOUT_SET)
            val document = exportToText()

            wipeEverything()
            import(source(document)).toList()

            assertEquals(before, db.dumpTable(BackupTable.WORKOUT_SET))
            val performed = db.dumpTable(BackupTable.WORKOUT_SET).first { it["id"] == "1" }
            assertEquals("85", performed["rpe"])
            assertEquals("100000000", performed["weight"])
            assertEquals(FINISHED_AT.toString(), performed["completed_at"])
        }

    /** Null is not blank, and a restore must not normalise it into one. */
    @Test
    fun `a null column comes back null rather than defaulted`() =
        runBlocking {
            db.seedEveryTable()
            val document = exportToText()

            wipeEverything()
            import(source(document)).toList()

            val unnamed = db.dumpTable(BackupTable.WORKOUT_SESSION).first { it["id"] == "2" }
            assertEquals(null, unnamed["name"])
            assertEquals(null, unnamed["template_id"])
            assertEquals(null, unnamed["completed_at"])
        }

    /**
     * Import replaces. A row on the device and absent from the file is gone afterwards — which
     * is the whole semantics, and the one a merge would have got wrong.
     */
    @Test
    fun `importing over a populated database removes what the file does not carry`() =
        runBlocking {
            db.seedEveryTable()
            val document = exportToText()
            db.backupDao().insertTags(listOf(tag(name = "added later").copy(id = 99)))
            assertEquals(3, db.dumpTable(BackupTable.TAG).size)

            import(source(document)).toList()

            assertEquals(2, db.dumpTable(BackupTable.TAG).size)
            assertTrue(db.dumpTable(BackupTable.TAG).none { it["name"] == "added later" })
        }

    /** A fresh install exports an empty document, and it has to restore onto a populated device. */
    @Test
    fun `importing an empty document empties the database`() =
        runBlocking {
            db.seedEveryTable()
            val empty = ExportDocument(
                exportedAt = EXPORTED_AT,
                appVersion = APP_VERSION,
                preferences = preferencesPayload(),
            )

            val progress = import(source(json.encodeToString(ExportDocument.serializer(), empty))).toList()

            assertEquals(ImportProgress.Complete(rowsWritten = 0), progress.last())
            BackupTable.entries.forEach { assertEquals("${it.tableName} is not empty", 0, db.dumpTable(it).size) }
        }

    /**
     * The second acceptance criterion, and the reason the two claim tables are in the format at
     * all: re-running the cascade over a restored session must award nothing. It lives in
     * [BackupIdempotencyTest], which drives the real pipeline; this is the half that proves the
     * rows the fence is made of came back.
     */
    @Test
    fun `the claim rows that fence a session come back with it`() =
        runBlocking {
            db.seedEveryTable()
            val document = exportToText()

            wipeEverything()
            import(source(document)).toList()

            assertEquals(listOf(mapOf("session_id" to "1")), db.dumpTable(BackupTable.SESSION_XP_CLAIM))
            assertEquals(listOf(mapOf("session_id" to "1")), db.dumpTable(BackupTable.SESSION_LOOT_CLAIM))
        }

    /** PERSISTENCE.md: a row from a newer version is a record, not a corruption. */
    @Test
    fun `an achievement this build does not recognise survives`() =
        runBlocking {
            db.seedEveryTable()
            val document = exportToText()

            wipeEverything()
            import(source(document)).toList()

            assertTrue(db.dumpTable(BackupTable.ACHIEVEMENT).any { it["achievement_id"] == "from_a_newer_version" })
        }

    /** Two drops of one collectible are two things the user holds; an import must not merge them. */
    @Test
    fun `a collectible held twice comes back twice`() =
        runBlocking {
            db.seedEveryTable()
            val document = exportToText()

            wipeEverything()
            import(source(document)).toList()

            assertEquals(2, db.dumpTable(BackupTable.COLLECTED_ITEM).count { it["collectible_id"] == "steel_shaker" })
        }

    /**
     * An unrecognised `schema_version` leaves **every** table byte-identical to before the
     * attempt — asserted against a dump taken first, not against the fixture, so a wipe that
     * happened and was undone would still fail.
     */
    @Test
    fun `a document at an unknown schema version changes nothing`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()
            val newer = exportToText().replace("\"schema_version\":1", "\"schema_version\":2")

            val progress = import(source(newer)).toList()

            assertEquals(
                ImportProgress.Failed(ImportError.UnsupportedSchemaVersion(found = 2, supported = 1)),
                progress.last(),
            )
            assertEquals(before, db.dumpEverything())
        }

    @Test
    fun `a document that does not parse changes nothing`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val progress = import(source("{ this is not a backup")).toList()

            assertTrue((progress.last() as ImportProgress.Failed).error is ImportError.MalformedJson)
            assertEquals(before, db.dumpEverything())
        }

    /**
     * The other acceptance criterion: a `workout_set` pointing at a `session_exercise` that is
     * not in the file. Refused, and the database is untouched — because the check happens
     * before the wipe, which is only possible because import replaces rather than merges.
     */
    @Test
    fun `a document with a dangling foreign key changes nothing`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()
            val document = decode(exportToText())
            val orphaned = document.copy(
                workoutSet = document.workoutSet + WorkoutSetRow(
                    id = 99,
                    sessionExerciseId = 999,
                    setNumber = 1,
                    completedAt = 0,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )

            val progress = import(source(json.encodeToString(ExportDocument.serializer(), orphaned))).toList()

            val error = (progress.last() as ImportProgress.Failed).error
            assertTrue("expected a constraint violation, was $error", error is ImportError.ConstraintViolation)
            assertEquals(before, db.dumpEverything())
        }

    /** The live session and its draft name ids from before the wipe, so a restore forgets both. */
    @Test
    fun `a successful import ends the live session and clears its draft`() =
        runBlocking {
            db.seedEveryTable()
            val document = exportToText()
            sessionState.update(SessionState.Active(sessionId = 1, startedAt = STARTED_AT))

            import(source(document)).toList()

            assertEquals(SessionState.Idle, sessionState.observe().first())
            assertEquals(1, draft.clearCount)
        }

    /** The envelope is diagnostic, and has to survive being written and read. */
    @Test
    fun `the exported document carries the moment and the build that wrote it`() =
        runBlocking {
            db.seedEveryTable()

            val document = decode(exportToText())

            assertEquals(EXPORTED_AT, document.exportedAt)
            assertEquals(APP_VERSION, document.appVersion)
            assertEquals(ExportDocument.SCHEMA_VERSION, document.schemaVersion)
        }

    /** Two exports of one database are the same file — nothing in the format depends on read order. */
    @Test
    fun `exporting twice produces the same document`() =
        runBlocking {
            db.seedEveryTable()

            assertEquals(exportToText(), exportToText())
        }

    /** A sanity check on the fixture: the wipe really does empty everything it is compared against. */
    @Test
    fun `the wipe used by these tests leaves nothing behind`() =
        runBlocking {
            db.seedEveryTable()
            assertNotEquals(0, db.dumpTable(BackupTable.WORKOUT_SET).size)

            wipeEverything()

            BackupTable.entries.forEach { assertEquals("${it.tableName} is not empty", 0, db.dumpTable(it).size) }
        }

    private suspend fun exportToText(): String {
        val text = StringBuilder()
        export(ExportSink { text.append(it) }, APP_VERSION).toList()
        return text.toString()
    }

    /** Empties the database through the same transaction an import uses, with an empty document. */
    private suspend fun wipeEverything() {
        backup.replaceAll(
            ExportDocument(exportedAt = EXPORTED_AT, appVersion = APP_VERSION, preferences = preferencesPayload()),
        ) { _, _ -> }
    }

    private fun decode(text: String) = (ExportFormat.decode(source(text)) as ExportFormat.Decoded.Success).document

    /**
     * [text] as the importer takes it: something to open, not something to hold.
     *
     * A test has the document as a string because it just built it; a screen will hand over a
     * content URI and never materialise one. See [ImportSource].
     */
    private fun source(text: String) = ImportSource { text.byteInputStream() }

    private fun rowCount(dump: Map<BackupTable, List<Map<String, String?>>>) = dump.values.sumOf { it.size.toLong() }

    /** The settings as the store holds them today — what an export of this device would carry. */
    private suspend fun preferencesPayload() = preferences.observe().first().toPayload()

    private companion object {
        const val EXPORTED_AT = 1_788_686_970_491L
        const val APP_VERSION = "1.0-debug"
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}

/** Reads the top-level keys of [text], for the tests that assert on the document's shape. */
internal fun topLevelKeys(text: String): Set<String> = Json.parseToJsonElement(text).jsonObject.keys
