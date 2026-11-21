package com.titan.core.domain.importer

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.model.UnitSystem
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.time.ZoneOffset

/**
 * The safety net under every parser: whatever a parser does with a file nobody vouches for, the
 * engine gets a typed answer back rather than an exception.
 */
class HistoryParserTest {

    private val context = ImportContext(zone = ZoneOffset.UTC, unitSystem = UnitSystem.METRIC)
    private val bytes = ImportSource { "x".repeat(100).byteInputStream() }

    private fun parser(read: (ImportSource) -> ParseResult) =
        object : HistoryParser {
            override fun parse(
                source: ImportSource,
                context: ImportContext,
            ): ParseResult = read(source)
        }

    @Test
    fun `a parser's own answer comes back as it gave it`() {
        val outcome = parser { ParseResult.NotRecognised }.parseSafely(bytes, context, maxBytes = 1_000)

        assertEquals(ParseOutcome.Read(ParseResult.NotRecognised), outcome)
    }

    @Test
    fun `reading past the cap stops the parse and says how large the cap is`() {
        val outcome = parser { source ->
            source.open().use { it.readBytes() }
            ParseResult.Parsed(ImportedHistory())
        }.parseSafely(bytes, context, maxBytes = 10)

        assertEquals(ParseOutcome.TooLarge(10), outcome)
    }

    @Test
    fun `a file that cannot be read is unreadable, not malformed`() {
        val outcome = parser { throw IOException("permission revoked") }.parseSafely(bytes, context, maxBytes = 1_000)

        assertEquals(ParseOutcome.Unreadable("permission revoked"), outcome)
    }

    @Test
    fun `a parser that trips over a shape it did not expect has the file refused, not the app crashed`() {
        val outcome = parser {
            listOf<String>()[3]
            ParseResult.NotRecognised
        }.parseSafely(
            bytes,
            context,
            maxBytes = 1_000,
        )

        assertEquals(ParseOutcome.Read::class, outcome::class)
        assertEquals(ParseResult.Malformed::class, (outcome as ParseOutcome.Read).result::class)
    }

    @Test
    fun `cancellation is not a malformed file`() {
        assertThrows(CancellationException::class.java) {
            parser { throw CancellationException("gone") }.parseSafely(bytes, context, maxBytes = 1_000)
        }
    }
}
