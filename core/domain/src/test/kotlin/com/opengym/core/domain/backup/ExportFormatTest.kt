package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.ExportDocument
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a document looks like on the way out, and that streaming it produces the same one.
 *
 * The refusals live in [ExportFormatDecodeTest]; this half is about the bytes.
 */
class ExportFormatTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /**
     * The load-bearing one. [ExportWriter] hand-rolls the JSON so it never holds the history,
     * which is only safe while it produces exactly what kotlinx would have — otherwise the
     * exporter's encoder and the importer's decoder are two formats that happen to agree today.
     */
    @Test
    fun `a streamed document is byte-identical to the encoded one`() =
        runTest {
            val document = sampleDocument()

            val streamed = stream(document)

            assertEquals(json.encodeToString(ExportDocument.serializer(), document), streamed)
        }

    @Test
    fun `a streamed document decodes back into the document it was written from`() =
        runTest {
            val document = sampleDocument()

            val decoded = ExportFormat.decode(stream(document).asSource())

            assertEquals(ExportFormat.Decoded.Success(document), decoded)
        }

    /**
     * The page size is a memory trade and must not be a format decision: a document written one
     * row at a time and one written in a single page have to be the same bytes.
     */
    @Test
    fun `the page size does not change the document`() =
        runTest {
            val document = sampleDocument()

            assertEquals(stream(document, pageSize = Int.MAX_VALUE), stream(document, pageSize = 1))
        }

    @Test
    fun `every table is a top-level key, empty ones included`() =
        runTest {
            val empty = ExportDocument(
                exportedAt = EXPORTED_AT,
                appVersion = APP_VERSION,
                preferences = samplePreferences(),
            )

            val keys = json.parseToJsonElement(stream(empty)).jsonObject.keys

            BackupTable.entries.forEach { table ->
                assertTrue("${table.tableName} is missing from the document", table.tableName in keys)
            }
            assertEquals(ENVELOPE_KEYS + BackupTable.entries.map { it.tableName }, keys.toList())
        }

    @Test
    fun `the envelope carries the version, the moment and the writing build`() =
        runTest {
            val root = json.parseToJsonElement(stream(sampleDocument())).jsonObject

            assertEquals(ExportDocument.SCHEMA_VERSION.toString(), root.getValue("schema_version").toString())
            assertEquals(EXPORTED_AT.toString(), root.getValue("exported_at").toString())
            assertEquals("\"$APP_VERSION\"", root.getValue("app_version").toString())
            assertNotNull(root["preferences"])
        }

    /** Row keys are column names, so a reader can line a file up against the exported schema. */
    @Test
    fun `a row's keys are its table's column names, in the table's order`() =
        runTest {
            val root = json.parseToJsonElement(stream(sampleDocument())).jsonObject

            val set = root.getValue("workout_set").jsonArray.first().jsonObject

            assertEquals(
                listOf(
                    "id", "session_exercise_id", "set_number", "reps", "weight", "duration", "distance",
                    "heart_rate_avg", "heart_rate_max", "rest_after", "rpe", "set_type", "completed_at",
                    "is_completed", "notes", "created_at", "updated_at",
                ),
                set.keys.toList(),
            )
        }

    /** A null column is written as `null`, not dropped — so a row carries exactly its table's columns. */
    @Test
    fun `a null column is written rather than omitted`() =
        runTest {
            val root = json.parseToJsonElement(stream(sampleDocument())).jsonObject

            val unnamed = root.getValue("workout_session").jsonArray[1].jsonObject

            assertEquals("null", unnamed.getValue("name").toString())
            assertEquals("null", unnamed.getValue("template_id").toString())
            assertEquals("null", unnamed.getValue("completed_at").toString())
        }

    @Test
    fun `rowCount is every table's rows added up`() {
        val document = sampleDocument()

        assertEquals(BackupTable.entries.sumOf { document.rows(it).size }.toLong(), document.rowCount)
    }

    /** The fixture has to reach every table, or half of what these tests assert is vacuous. */
    @Test
    fun `every table is reachable through rows`() {
        val document = sampleDocument()

        BackupTable.entries.forEach { table ->
            assertTrue("${table.tableName} has no rows in the fixture", document.rows(table).isNotEmpty())
        }
    }

    private companion object {

        /** The document's non-table keys, in the order [ExportDocument] declares them. */
        val ENVELOPE_KEYS = listOf("schema_version", "exported_at", "app_version", "preferences")
    }
}

/**
 * Streams [document] through an [ExportWriter] and returns the whole text it produced.
 *
 * Top-level so [ExportFormatDecodeTest] and [ExportWriterTest] share one driver, and so the
 * protocol the writer documents — header, tables in order, finish — is spelled out once. For a
 * document too big to want as a value, use [writeTo] and give it somewhere to go.
 */
internal suspend fun stream(
    document: ExportDocument,
    pageSize: Int = 2,
): String {
    val text = StringBuilder()
    writeTo(ExportSink { chunk -> text.append(chunk) }, document, pageSize)
    return text.toString()
}

/**
 * Writes [document] into [sink] a page at a time, in the order [ExportWriter] documents.
 *
 * The whole of the export protocol as a test drives it: header, every table in [BackupTable]
 * order — empty ones included, because the document's key set is constant — then finish.
 */
internal suspend fun writeTo(
    sink: ExportSink,
    document: ExportDocument,
    pageSize: Int,
) {
    val writer = ExportWriter(sink)
    writer.writeHeader(document.exportedAt, document.appVersion, document.preferences)
    BackupTable.entries.forEach { table ->
        val rows = document.rows(table)
        if (rows.isEmpty()) {
            writer.writeRows(table, rows)
        } else {
            rows.chunked(pageSize.coerceAtMost(rows.size)).forEach { writer.writeRows(table, it) }
        }
    }
    writer.finish()
}
