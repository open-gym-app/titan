package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.MuscleGroupRow
import com.titan.core.domain.backup.model.TagRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer's protocol, and what it does when a caller gets it wrong.
 *
 * Every misuse here produces a document that parses into something other than what was
 * exported — a table written twice, a table skipped and then gone back to, an unclosed object.
 * Throwing is the only honest answer: the alternative is a backup file that restores a
 * different database from the one it was taken of, discovered a phone later.
 */
class ExportWriterTest {

    private val text = StringBuilder()
    private val writer = ExportWriter(ExportSink { text.append(it) })
    private val json = Json { encodeDefaults = true }

    @Test
    fun `a document with no rows at all still carries every table`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            writer.finish()

            val root = json.parseToJsonElement(text.toString()).jsonObject
            BackupTable.entries.forEach { table ->
                assertTrue("${table.tableName} is missing", root.getValue(table.tableName).jsonArray.isEmpty())
            }
        }

    @Test
    fun `a table filled over several calls holds every row, in the order it was given them`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            writer.writeRows(BackupTable.MUSCLE_GROUP, listOf(MuscleGroupRow(id = 1, name = "CHEST")))
            writer.writeRows(BackupTable.MUSCLE_GROUP, listOf(MuscleGroupRow(id = 2, name = "BACK")))
            writer.writeRows(BackupTable.MUSCLE_GROUP, listOf(MuscleGroupRow(id = 3, name = "LEGS")))
            writer.finish()

            val names = json.parseToJsonElement(text.toString())
                .jsonObject.getValue("muscle_group").jsonArray
                .map { it.jsonObject.getValue("name").toString() }
            assertEquals(listOf("\"CHEST\"", "\"BACK\"", "\"LEGS\""), names)
        }

    /** Skipping a table is how a caller says it was empty; the document must still declare it. */
    @Test
    fun `tables skipped between two written ones are written as empty arrays`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            writer.writeRows(BackupTable.MUSCLE_GROUP, listOf(MuscleGroupRow(id = 1, name = "CHEST")))
            writer.writeRows(BackupTable.COLLECTED_ITEM, emptyList())
            writer.finish()

            val root = json.parseToJsonElement(text.toString()).jsonObject
            assertEquals(1, root.getValue("muscle_group").jsonArray.size)
            assertTrue(root.getValue("tag").jsonArray.isEmpty())
            assertTrue(root.getValue("workout_set").jsonArray.isEmpty())
            assertTrue(root.getValue("collected_item").jsonArray.isEmpty())
        }

    @Test
    fun `an empty page still opens its table`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            writer.writeRows(BackupTable.TAG, emptyList())
            writer.finish()

            assertTrue(json.parseToJsonElement(text.toString()).jsonObject.getValue("tag").jsonArray.isEmpty())
        }

    @Test
    fun `rows before the header are refused`() =
        runTest {
            assertRefuses { writer.writeRows(BackupTable.TAG, listOf(TagRow(id = 1, name = "PUSH"))) }
        }

    @Test
    fun `finishing before the header is refused`() =
        runTest {
            assertRefuses { writer.finish() }
        }

    @Test
    fun `a second header is refused`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())

            assertRefuses { writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences()) }
        }

    /** A second `}` would make the file unparseable, which is worse than a loud failure. */
    @Test
    fun `finishing twice is refused`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            writer.finish()

            assertRefuses { writer.finish() }
        }

    @Test
    fun `rows after finishing are refused`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            writer.finish()

            assertRefuses { writer.writeRows(BackupTable.TAG, listOf(TagRow(id = 1, name = "PUSH"))) }
        }

    /**
     * Going back to a closed table would need bytes the sink has already been handed, so the
     * writer refuses rather than silently writing the key twice — which is a document whose
     * second `muscle_group` array wins and whose first is lost.
     */
    @Test
    fun `going back to an earlier table is refused`() =
        runTest {
            writer.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            writer.writeRows(BackupTable.EXERCISE, emptyList())

            assertRefuses { writer.writeRows(BackupTable.MUSCLE_GROUP, listOf(MuscleGroupRow(id = 1, name = "CHEST"))) }
        }

    /** Chunks concatenate to the document and nothing more: the sink sees no reordering or padding. */
    @Test
    fun `the sink is handed the document one chunk at a time`() =
        runTest {
            val chunks = mutableListOf<String>()
            val chunked = ExportWriter(ExportSink { chunks += it })

            chunked.writeHeader(EXPORTED_AT, APP_VERSION, samplePreferences())
            chunked.writeRows(BackupTable.MUSCLE_GROUP, listOf(MuscleGroupRow(id = 1, name = "CHEST")))
            chunked.finish()

            assertTrue("the writer should not build the document in one go", chunks.size > 1)
            assertEquals(
                1,
                json.parseToJsonElement(chunks.joinToString("")).jsonObject.getValue("muscle_group").jsonArray.size,
            )
        }

    /**
     * `runCatching` rather than `assertThrows`, because every call here suspends and
     * `assertThrows` takes a blocking lambda — a nested `runTest` to bridge that would be a
     * second test scheduler inside the first.
     */
    private suspend fun assertRefuses(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("expected the writer to refuse, got $failure", failure is IllegalStateException)
    }
}
