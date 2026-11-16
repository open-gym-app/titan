package com.titan.core.domain.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SourceValuesTest {

    private val newYork = ZoneId.of("America/New_York")

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    // ------------------------------------------------------------------ decimal

    @Test
    fun `a number reads with a decimal point or a decimal comma`() {
        assertEquals(62.5, SourceValues.decimal("62.5")!!, 0.0)
        assertEquals(62.5, SourceValues.decimal("62,5")!!, 0.0)
        assertEquals(80.0, SourceValues.decimal(" 80 ")!!, 0.0)
        assertEquals(0.5, SourceValues.decimal(".5")!!, 0.0)
        assertEquals(10.0, SourceValues.decimal("10.")!!, 0.0)
    }

    @Test
    fun `a sign is kept, because range is the planner's to judge`() {
        assertEquals(-5.0, SourceValues.decimal("-5")!!, 0.0)
        assertEquals(5.0, SourceValues.decimal("+5")!!, 0.0)
    }

    @Test
    fun `a single separator is the decimal one, and only an unambiguous group reads as thousands`() {
        assertEquals(1.234, SourceValues.decimal("1,234")!!, 0.0)
        assertEquals(1.234, SourceValues.decimal("1.234")!!, 0.0)
        assertEquals(1_234_567.0, SourceValues.decimal("1,234,567")!!, 0.0)
        assertEquals(1_234.5, SourceValues.decimal("1,234.5")!!, 0.0)
        assertEquals(1_234.5, SourceValues.decimal("1.234,5")!!, 0.0)
        assertEquals(1_234_567.0, SourceValues.decimal("1.234.567")!!, 0.0)
    }

    @Test
    fun `blank and anything that is not a plain number read as null`() {
        listOf(null, "", "  ", "heavy", "1e5", "NaN", "Infinity", "1.2.3", "12kg", "1,2,3", "--1").forEach { text ->
            assertNull(text, SourceValues.decimal(text))
        }
    }

    // ------------------------------------------------------------------ elapsedSeconds

    @Test
    fun `a bare number is seconds, rounded`() {
        assertEquals(4_419L, SourceValues.elapsedSeconds("4419"))
        assertEquals(120L, SourceValues.elapsedSeconds("120.0"))
        assertEquals(31L, SourceValues.elapsedSeconds("30,6"))
    }

    @Test
    fun `units spelled out read largest first, in any of their spellings`() {
        assertEquals(3_900L, SourceValues.elapsedSeconds("1h 5m"))
        assertEquals(2_280L, SourceValues.elapsedSeconds("38min"))
        assertEquals(7_200L, SourceValues.elapsedSeconds("2h"))
        assertEquals(45L, SourceValues.elapsedSeconds("45 s"))
        assertEquals(3_930L, SourceValues.elapsedSeconds("1h5m30s"))
        assertEquals(950_460L, SourceValues.elapsedSeconds("264h 1m"))
        assertEquals(5_430L, SourceValues.elapsedSeconds("1 hour 30 minutes 30 seconds"))
        assertEquals(3_900L, SourceValues.elapsedSeconds("1H 5M"))
    }

    @Test
    fun `a clock is hours, minutes and seconds, or minutes and seconds when it has two parts`() {
        assertEquals(3_723L, SourceValues.elapsedSeconds("1:02:03"))
        assertEquals(150L, SourceValues.elapsedSeconds("2:30"))
    }

    @Test
    fun `blank, units out of order and anything else read as null`() {
        listOf(null, "", " ", "soon", "5m 1h", "1:75", "h", "1d").forEach { text ->
            assertNull(text, SourceValues.elapsedSeconds(text))
        }
    }

    // ------------------------------------------------------------------ localTimestamp

    @Test
    fun `wall-clock time is read in the zone the user is in`() {
        assertEquals(millis("2024-03-02T18:04:11Z"), SourceValues.localTimestamp("2024-03-02 18:04:11", ZoneOffset.UTC))
        assertEquals(millis("2024-03-02T23:04:11Z"), SourceValues.localTimestamp("2024-03-02 18:04:11", newYork))
    }

    @Test
    fun `a T separator, missing seconds and a fraction of one all read`() {
        assertEquals(millis("2024-03-02T18:04:00Z"), SourceValues.localTimestamp("2024-03-02T18:04", ZoneOffset.UTC))
        assertEquals(millis("2024-03-02T18:04:00Z"), SourceValues.localTimestamp(" 2024-03-02 18:04 ", ZoneOffset.UTC))
        assertEquals(
            millis("2024-03-02T18:04:11.250Z"),
            SourceValues.localTimestamp("2024-03-02 18:04:11.25", ZoneOffset.UTC),
        )
    }

    @Test
    fun `a time that carries its own offset is read at it, whatever the device's zone`() {
        assertEquals(millis("2024-03-02T18:04:11Z"), SourceValues.localTimestamp("2024-03-02T18:04:11Z", newYork))
        assertEquals(millis("2024-03-02T17:04:11Z"), SourceValues.localTimestamp("2024-03-02T18:04:11+01:00", newYork))
    }

    @Test
    fun `a time a clock change skipped moves forward, and one it repeated is the earlier`() {
        // 02:30 never happened in New York on 10 March 2024; 01:30 happened twice on 3 November.
        assertEquals(millis("2024-03-10T07:30:00Z"), SourceValues.localTimestamp("2024-03-10 02:30:00", newYork))
        assertEquals(millis("2024-11-03T05:30:00Z"), SourceValues.localTimestamp("2024-11-03 01:30:00", newYork))
    }

    @Test
    fun `a date that does not exist or does not read is null`() {
        listOf(
            null,
            "",
            "yesterday",
            "2024-02-30 10:00:00",
            "02/03/2024 18:04",
            "2024-03-02",
            "2024-03-02 25:00:00",
        ).forEach { text ->
            assertNull(text, SourceValues.localTimestamp(text, ZoneOffset.UTC))
        }
    }

    @Test
    fun `a year too far out to count in milliseconds is null rather than an overflow`() {
        assertNull(SourceValues.localTimestamp("+999999999-12-31 23:59:59", ZoneOffset.UTC))
    }
}
