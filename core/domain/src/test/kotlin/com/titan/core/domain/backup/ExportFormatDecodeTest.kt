package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.PreferencesPayload
import com.titan.core.domain.backup.model.WorkoutSetRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream

/**
 * Every way [ExportFormat.decode] refuses a document, and the one way it does not.
 *
 * The version is checked *before* the body is interpreted, so a file from a newer build is
 * refused for the reason a user can act on — "written by a newer version of Titan" — rather
 * than incidentally, by the decoder tripping over a table it has never heard of.
 */
class ExportFormatDecodeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `a newer schema version is refused for being another version`() {
        val decoded = ExportFormat.decode(source(documentAtVersion(ExportDocument.SCHEMA_VERSION + 1)))

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
        val decoded = ExportFormat.decode(source(documentAtVersion(0)))

        assertEquals(
            refusal(ImportError.UnsupportedSchemaVersion(found = 0, supported = ExportDocument.SCHEMA_VERSION)),
            decoded,
        )
    }

    @Test
    fun `text that is not JSON is malformed`() {
        assertMalformed(ExportFormat.decode(source("not a document")))
    }

    @Test
    fun `an empty document is malformed`() {
        assertMalformed(ExportFormat.decode(source("")))
    }

    @Test
    fun `a JSON array is not a document`() {
        assertMalformed(ExportFormat.decode(source("[]")))
    }

    @Test
    fun `JSON without a version is malformed rather than assumed to be current`() {
        val decoded = ExportFormat.decode(source("""{"exported_at":1,"app_version":"x"}"""))

        val error = assertMalformed(decoded)
        assertTrue("the message should name the key: ${error.reason}", error.reason.contains("schema_version"))
    }

    @Test
    fun `a version that is not a number is malformed`() {
        assertMalformed(ExportFormat.decode(source("""{"schema_version":"one"}""")))
    }

    /** The right version and the wrong shape: refused on the shape, once the version has passed. */
    @Test
    fun `a document at this version whose envelope is wrong is malformed`() {
        assertMalformed(ExportFormat.decode(source("""{"schema_version":1,"exported_at":"yesterday"}""")))
    }

    @Test
    fun `a document with no preferences is malformed`() {
        assertMalformed(ExportFormat.decode(source("""{"schema_version":1,"exported_at":1,"app_version":"x"}""")))
    }

    @Test
    fun `a row missing a required column is malformed`() =
        runTest {
            val truncated = stream(sampleDocument()).replace(""""name":"CHEST",""", "")

            assertMalformed(ExportFormat.decode(source(truncated)))
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

            val decoded = ExportFormat.decode(source(withExtra))

            assertEquals(document, (decoded as ExportFormat.Decoded.Success).document)
        }

    /**
     * The other half of that tolerance: a file written before `body_measurement` gained its five
     * extra sites carries none of their keys, and restores with them null — what the row would
     * have held — rather than being refused for a column it never had.
     */
    @Test
    fun `a body measurement from before the extra sites restores with them empty`() =
        runTest {
            val document = sampleDocument()
            val older = listOf("neck", "shoulders", "hips", "forearm", "calf")
                .fold(stream(document)) { text, key -> text.replace(Regex("\"$key\":(null|\\d+),"), "") }

            val decoded = (ExportFormat.decode(source(older)) as ExportFormat.Decoded.Success).document

            val first = decoded.bodyMeasurement.first()
            assertEquals(
                listOf(null, null, null, null, null),
                listOf(first.neck, first.shoulders, first.hips, first.forearm, first.calf),
            )
            assertEquals(document.bodyMeasurement.first().chest, first.chest)
        }

    /** A table left out of a document at this version reads back empty rather than failing. */
    @Test
    fun `a missing table reads back as no rows`() {
        val preferences = json.encodeToString(PreferencesPayload.serializer(), samplePreferences())
        val minimal = """{"schema_version":1,"exported_at":1,"app_version":"x","preferences":$preferences}"""

        val document = (ExportFormat.decode(source(minimal)) as ExportFormat.Decoded.Success).document

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
            val decoded = ExportFormat.decode(source(stream(sampleDocument())))

            val ids = (decoded as ExportFormat.Decoded.Success).document.achievement.map { it.achievementId }
            assertEquals(listOf("first_workout", "from_a_newer_version"), ids)
        }

    /**
     * The bytes are read twice — the version, then the document — and never held as text.
     *
     * This is the whole of what makes an import fit in a phone's heap, so it is asserted rather
     * than left to the doc: a decoder that read the file into a `String`, or into a
     * `JsonElement` tree it then decoded from, would open the source once and hold tens of
     * megabytes while it did.
     */
    @Test
    fun `a document is streamed twice rather than held once`() =
        runTest {
            val source = source(stream(sampleDocument()))

            assertTrue(ExportFormat.decode(source) is ExportFormat.Decoded.Success)
            assertEquals(2, source.opened)
        }

    /**
     * And a document this build cannot read costs one pass, not two: the version refuses it
     * before a single row is turned into an object.
     */
    @Test
    fun `a document at another version is refused without being decoded`() {
        val source = source(documentAtVersion(ExportDocument.SCHEMA_VERSION + 1))

        ExportFormat.decode(source)

        assertEquals(1, source.opened)
    }

    /**
     * A file that cannot be read is not a malformed file. Nothing has been read, so nothing can
     * be said about its shape — and the two are different sentences on screen: one asks the user
     * to pick the file again, the other tells them their backup is broken.
     */
    @Test
    fun `a source that cannot be read is an IO failure`() {
        val decoded = ExportFormat.decode { throw IOException("permission denied") }

        assertEquals(ExportFormat.Decoded.Refused(ImportError.IoFailure("permission denied")), decoded)
    }

    /** The same, for bytes that stop arriving halfway through — a truncated read, not a truncated file. */
    @Test
    fun `a stream that fails mid-document is an IO failure`() =
        runTest {
            val text = stream(sampleDocument())

            val decoded = ExportFormat.decode { FailingStream(text.byteInputStream(), failAfter = 64) }

            assertEquals(ExportFormat.Decoded.Refused(ImportError.IoFailure(CARD_REMOVED)), decoded)
        }

    /**
     * A source is opened twice, and nothing makes it serve the same bytes both times: a
     * provider can answer the second opening with another file. The version the first pass
     * accepted is not taken on trust for the second (#257).
     */
    @Test
    fun `a document that changes version between the two passes is refused`() =
        runTest {
            val newer = documentAtVersion(ExportDocument.SCHEMA_VERSION + 1)
            val passes = ArrayDeque(listOf(stream(sampleDocument()), newer))

            val decoded = ExportFormat.decode { passes.removeFirst().byteInputStream() }

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

    /** The limit holds on the size of the bytes, not of the document they would decode into. */
    @Test
    fun `a document past the size limit is refused as too large`() =
        runTest {
            val text = stream(sampleDocument())
            val bytes = text.toByteArray().size.toLong()

            val decoded = ExportFormat.decode(source(text), maxBytes = bytes - 1)

            assertEquals(refusal(ImportError.DocumentTooLarge(limitBytes = bytes - 1)), decoded)
        }

    @Test
    fun `a document exactly at the size limit decodes`() =
        runTest {
            val text = stream(sampleDocument())

            val decoded = ExportFormat.decode(source(text), maxBytes = text.toByteArray().size.toLong())

            assertEquals(sampleDocument(), (decoded as ExportFormat.Decoded.Success).document)
        }

    /**
     * The real limit, against a file that never ends: a `{` and then whitespace for ever, which
     * the version pass would otherwise walk until the heap or the disk gave out. It stops at the
     * limit, having built nothing, and says why.
     */
    @Test
    fun `a stream that never ends is refused at the real limit`() {
        val decoded = ExportFormat.decode { EndlessObject() }

        assertEquals(refusal(ImportError.DocumentTooLarge(limitBytes = ExportFormat.MAX_DOCUMENT_BYTES)), decoded)
    }

    /**
     * The scale the app commits to, through the decoder: 50,000 sets, written to a file by the
     * streaming writer and read back off it.
     *
     * A JVM test cannot honestly assert a heap figure — `ExportStreamingTest` says why — but it
     * can assert that the path a phone takes is the one the tests take, and at the size that
     * makes the difference matter. The document that comes back is the only thing this holds:
     * the text was a file, and the decoder never saw more than a batch of it at a time.
     */
    @Test
    fun `a target-scale document decodes off a file`() =
        runTest {
            val file = temporaryFolder.newFile("export.json")
            val document = targetScaleDocument()
            file.bufferedWriter().use { out -> writeTo(ExportSink { out.write(it) }, document, pageSize = 500) }

            val decoded = ExportFormat.decode { file.inputStream() }

            assertEquals(document, (decoded as ExportFormat.Decoded.Success).document)
            assertTrue(
                "a ${file.length()}-byte fixture is too small to say anything about a phone's heap",
                file.length() > MIN_MEANINGFUL_BYTES,
            )
            // The size limit has to leave a real history room to grow: MAX_DOCUMENT_BYTES claims
            // about four times this, and a format change that ate the headroom should say so here.
            assertTrue(
                "a target-scale export of ${file.length()} bytes leaves too little room under the limit",
                file.length() * TARGET_SCALE_HEADROOM <= ExportFormat.MAX_DOCUMENT_BYTES,
            )
        }

    /**
     * A source over [text], counting its openings.
     *
     * The count is the contract [ImportSource] states — the decoder opens the same bytes twice,
     * once for the version and once for the document — and two tests above assert on it.
     */
    private fun source(text: String) = CountingSource(text)

    /** An [ImportSource] over a string, which is what a test holds and a screen must not. */
    private class CountingSource(private val text: String) : ImportSource {
        var opened = 0
            private set

        override fun open(): InputStream {
            opened++
            return text.byteInputStream()
        }
    }

    /** `{` followed by spaces, without end. Bulk reads fill the whole buffer, as a file would. */
    private class EndlessObject : InputStream() {
        private var opened = false

        override fun read(): Int =
            if (opened) {
                ' '.code
            } else {
                opened = true
                '{'.code
            }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            var start = offset
            if (!opened) {
                buffer[start++] = '{'.code.toByte()
                opened = true
            }
            buffer.fill(' '.code.toByte(), start, offset + length)
            return length
        }
    }

    /** A stream that stops being readable partway through, the way a removed card does. */
    private class FailingStream(
        private val delegate: InputStream,
        private val failAfter: Int,
    ) : InputStream() {
        private var read = 0

        override fun read(): Int {
            if (read++ >= failAfter) throw IOException(CARD_REMOVED)
            return delegate.read()
        }
    }

    /**
     * [sampleDocument] with `workout_set` grown to the scale the app commits to.
     *
     * The sets alone, because they are the table that gets big — 50,000 of them against 1,000
     * sessions is `LargeHistoryBenchmarkTest.TARGET_SCALE` — and because the point is the size
     * of the file, not its shape. The rows are not referentially sound and do not need to be:
     * [ExportFormat] decodes, `BackupValidator` judges, and only the first is under test here.
     */
    private fun targetScaleDocument(): ExportDocument {
        val sample = sampleDocument()
        val filler = (1..TARGET_SET_COUNT).map { index ->
            WorkoutSetRow(
                id = FILLER_ID_BASE + index,
                sessionExerciseId = 1,
                setNumber = index,
                weight = 100_000_000,
                reps = 10,
                rpe = 85,
                completedAt = index.toLong(),
                createdAt = index.toLong(),
                updatedAt = index.toLong(),
            )
        }
        return sample.copy(workoutSet = sample.workoutSet + filler)
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

    private companion object {

        /** The set count of `LargeHistoryBenchmarkTest.TARGET_SCALE`: the size a real history reaches. */
        const val TARGET_SET_COUNT = 50_000

        /** Clear of the fixture's own ids, so the filler cannot collide with them. */
        const val FILLER_ID_BASE = 1_000L

        /**
         * The size the scale test would mean nothing under, in bytes.
         *
         * 50,000 sets encode to roughly 15 MB, so a megabyte is a floor the fixture clears by an
         * order of magnitude — loose enough that a column dropped from `workout_set` cannot fail
         * it, tight enough that a fixture which silently stopped being generated would.
         */
        const val MIN_MEANINGFUL_BYTES = 1_000_000L

        /** How many target-scale exports must fit under [ExportFormat.MAX_DOCUMENT_BYTES]. */
        const val TARGET_SCALE_HEADROOM = 4L

        /** What [FailingStream] says when it gives up, asserted by the test that uses it. */
        const val CARD_REMOVED = "the card was removed"
    }
}
