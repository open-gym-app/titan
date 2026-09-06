package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.BackupTable
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.backup.model.PreferencesPayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every way [ExportFormat.decode] refuses a document, and the one way it does not.
 *
 * The version is checked *before* the body is interpreted, so a file from a newer build is
 * refused for the reason a user can act on — "written by a newer version of Open Gym" — rather
 * than incidentally, by the decoder tripping over a table it has never heard of.
 */
class ExportFormatDecodeTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `a newer schema version is refused for being another version`() {
        val decoded = ExportFormat.decode(documentAtVersion(ExportDocument.SCHEMA_VERSION + 1))

        assertEquals(
            refusal(
                ImportError.UnsupportedSchemaVersion(
                    found = ExportDocument.SCHEMA_VERSION + 1,
                    supported = ExportDocument.SCHEMA_VERSION,
                ),
            ),
            decoded,
        )
    }

    /** Older is refused too. That is the compatibility rule, not an oversight — see [ImportError]. */
    @Test
    fun `an older schema version is refused as well`() {
        val decoded = ExportFormat.decode(documentAtVersion(0))

        assertEquals(
            refusal(ImportError.UnsupportedSchemaVersion(found = 0, supported = ExportDocument.SCHEMA_VERSION)),
            decoded,
        )
    }

    @Test
    fun `text that is not JSON is malformed`() {
        assertMalformed(ExportFormat.decode("not a document"))
    }

    @Test
    fun `an empty document is malformed`() {
        assertMalformed(ExportFormat.decode(""))
    }

    @Test
    fun `a JSON array is not a document`() {
        assertMalformed(ExportFormat.decode("[]"))
    }

    @Test
    fun `JSON without a version is malformed rather than assumed to be current`() {
        val decoded = ExportFormat.decode("""{"exported_at":1,"app_version":"x"}""")

        val error = assertMalformed(decoded)
        assertTrue("the message should name the key: ${error.reason}", error.reason.contains("schema_version"))
    }

    @Test
    fun `a version that is not a number is malformed`() {
        assertMalformed(ExportFormat.decode("""{"schema_version":"one"}"""))
    }

    /** The right version and the wrong shape: refused on the shape, once the version has passed. */
    @Test
    fun `a document at this version whose envelope is wrong is malformed`() {
        assertMalformed(ExportFormat.decode("""{"schema_version":1,"exported_at":"yesterday"}"""))
    }

    @Test
    fun `a document with no preferences is malformed`() {
        assertMalformed(ExportFormat.decode("""{"schema_version":1,"exported_at":1,"app_version":"x"}"""))
    }

    @Test
    fun `a row missing a required column is malformed`() =
        runTest {
            val truncated = stream(sampleDocument()).replace(""""name":"CHEST",""", "")

            assertMalformed(ExportFormat.decode(truncated))
        }

    /**
     * A column added by a slightly newer build of the *same* version is skipped, not fatal —
     * that is the tolerance the version rule deliberately leaves room for, and the reason
     * adding a nullable column does not need a bump.
     */
    @Test
    fun `an unknown key inside a known version is ignored`() =
        runTest {
            val document = sampleDocument()
            val withExtra = stream(document).replaceFirst("""{"id":1,""", """{"from_the_future":true,"id":1,""")

            val decoded = ExportFormat.decode(withExtra)

            assertEquals(document, (decoded as ExportFormat.Decoded.Success).document)
        }

    /** A table left out of a document at this version reads back empty rather than failing. */
    @Test
    fun `a missing table reads back as no rows`() {
        val preferences = json.encodeToString(PreferencesPayload.serializer(), samplePreferences())
        val minimal = """{"schema_version":1,"exported_at":1,"app_version":"x","preferences":$preferences}"""

        val document = (ExportFormat.decode(minimal) as ExportFormat.Decoded.Success).document

        assertEquals(0L, document.rowCount)
        BackupTable.entries.forEach { assertTrue("${it.tableName} should be empty", document.rows(it).isEmpty()) }
    }

    /**
     * PERSISTENCE.md: a row naming a milestone this build does not recognise is an unlock from a
     * newer version, not a corrupt row, and a restore must preserve it. The format must not be
     * the thing that drops it.
     */
    @Test
    fun `an unrecognised achievement id survives the format`() =
        runTest {
            val decoded = ExportFormat.decode(stream(sampleDocument()))

            val ids = (decoded as ExportFormat.Decoded.Success).document.achievement.map { it.achievementId }
            assertEquals(listOf("first_workout", "from_a_newer_version"), ids)
        }

    private fun assertMalformed(decoded: ExportFormat.Decoded): ImportError.MalformedJson {
        val error = (decoded as ExportFormat.Decoded.Refused).error
        assertTrue("expected MalformedJson, was $error", error is ImportError.MalformedJson)
        return error as ImportError.MalformedJson
    }

    private fun refusal(error: ImportError) = ExportFormat.Decoded.Refused(error)

    /** [sampleDocument] re-encoded with its version replaced — the only way to make one this build refuses. */
    private fun documentAtVersion(version: Int): String {
        val encoded = json.encodeToJsonElement(ExportDocument.serializer(), sampleDocument()).jsonObject
        val bumped = encoded.toMutableMap().apply { put("schema_version", JsonPrimitive(version)) }
        return json.encodeToString(JsonObject.serializer(), JsonObject(bumped))
    }
}
