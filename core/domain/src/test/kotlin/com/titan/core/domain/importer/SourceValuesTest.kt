package com.titan.core.domain.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    fun `an offset after a space and without its colon, as Fitbod writes it, is read at it too`() {
        assertEquals(millis("2024-03-02T18:04:11Z"), SourceValues.localTimestamp("2024-03-02 18:04:11 +0000", newYork))
        assertEquals(millis("2024-03-02T12:34:11Z"), SourceValues.localTimestamp("2024-03-02 18:04:11 +0530", newYork))
        assertEquals(millis("2024-03-02T23:04:11Z"), SourceValues.localTimestamp("2024-03-02 18:04:11 -0500", newYork))
        assertEquals(millis("2024-03-02T17:04:00Z"), SourceValues.localTimestamp("2024-03-02 18:04+0100", newYork))
        assertEquals(millis("2024-03-02T17:04:11Z"), SourceValues.localTimestamp("2024-03-02 18:04:11 +01:00", newYork))
        // Two offsets that disagree, or one that is no offset, are not a time.
        listOf("2024-03-02 18:04:11 +0100Z", "2024-03-02 18:04:11 +2500", "2024-03-02 18:04:11 +01").forEach { text ->
            assertNull(text, SourceValues.localTimestamp(text, newYork))
        }
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

    // ------------------------------------------------------------------ epochSeconds

    @Test
    fun `seconds since 1970 are an instant, and zero or nonsense is no time at all`() {
        assertEquals(millis("2026-10-05T18:00:00Z"), SourceValues.epochSeconds("1791223200"))
        assertEquals(millis("2026-10-05T18:00:00Z"), SourceValues.epochSeconds(" 1791223200 "))
        listOf(null, "", "0", "-5", "1791223200.5", "soon", "99999999999999999").forEach { text ->
            assertNull(text, SourceValues.epochSeconds(text))
        }
    }

    // ------------------------------------------------------------------ localDateAtNoon

    @Test
    fun `a date with no time is noon that day, in the zone the user is in`() {
        assertEquals(millis("2024-03-02T12:00:00Z"), SourceValues.localDateAtNoon("2024-03-02", ZoneOffset.UTC))
        assertEquals(millis("2024-03-02T17:00:00Z"), SourceValues.localDateAtNoon(" 2024-03-02 ", newYork))
        assertEquals(millis("2024-03-02T12:00:00Z"), SourceValues.localDateAtNoon("2024/3/2", ZoneOffset.UTC))
    }

    @Test
    fun `noon is the same day in UTC east and west of it, and on the days the clocks change`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val honolulu = ZoneId.of("Pacific/Honolulu")
        assertEquals(millis("2024-03-02T03:00:00Z"), SourceValues.localDateAtNoon("2024-03-02", tokyo))
        assertEquals(millis("2024-03-02T22:00:00Z"), SourceValues.localDateAtNoon("2024-03-02", honolulu))
        // New York's clocks went forward on 10 March 2024 and back on 3 November.
        assertEquals(millis("2024-03-10T16:00:00Z"), SourceValues.localDateAtNoon("2024-03-10", newYork))
        assertEquals(millis("2024-11-03T17:00:00Z"), SourceValues.localDateAtNoon("2024-11-03", newYork))
    }

    @Test
    fun `a date that is not year first, does not exist or carries a time is null`() {
        listOf(
            null,
            "",
            "yesterday",
            "02/03/2024",
            "2024-02-30",
            "2024-13-01",
            "2024-03/02",
            "24-03-02",
            "2024-03-02 18:04:11",
        ).forEach { text ->
            assertNull(text, SourceValues.localDateAtNoon(text, ZoneOffset.UTC))
        }
    }

    @Test
    fun `a year-last date is noon too, once the order of its day and month is given`() {
        assertEquals(
            millis("2017-05-02T12:00:00Z"),
            SourceValues.localDateAtNoon("02/05/17", ZoneOffset.UTC, DayMonthOrder.DAY_FIRST),
        )
        assertEquals(
            millis("2017-02-05T12:00:00Z"),
            SourceValues.localDateAtNoon("02/05/17", ZoneOffset.UTC, DayMonthOrder.MONTH_FIRST),
        )
        assertNull(SourceValues.localDateAtNoon("+999999999-12-31", ZoneOffset.UTC))
    }

    // ------------------------------------------------------------------ calendarDate

    @Test
    fun `a calendar date is year first, or year last in the order given, with a two-digit year in the 2000s`() {
        assertEquals(LocalDate.of(2024, 3, 2), SourceValues.calendarDate("2024/03/02"))
        assertEquals(LocalDate.of(2024, 3, 2), SourceValues.calendarDate("2024-3-2", DayMonthOrder.MONTH_FIRST))
        assertEquals(LocalDate.of(2017, 5, 2), SourceValues.calendarDate(" 02/05/17 ", DayMonthOrder.DAY_FIRST))
        assertEquals(LocalDate.of(2015, 2, 13), SourceValues.calendarDate("02/13/2015", DayMonthOrder.MONTH_FIRST))
        assertEquals(LocalDate.of(2015, 2, 13), SourceValues.calendarDate("13.02.2015", DayMonthOrder.DAY_FIRST))
        assertEquals(LocalDate.of(2015, 2, 13), SourceValues.calendarDate("13-2-15", DayMonthOrder.DAY_FIRST))
    }

    @Test
    fun `a year-last date without an order, or one that does not exist in it, is null`() {
        assertNull(SourceValues.calendarDate("02/05/17"))
        assertNull(SourceValues.calendarDate("02/13/2015", DayMonthOrder.DAY_FIRST))
        assertNull(SourceValues.calendarDate("02/05.2017", DayMonthOrder.DAY_FIRST))
        assertNull(SourceValues.calendarDate("02/05/017", DayMonthOrder.DAY_FIRST))
        assertNull(SourceValues.calendarDate(null, DayMonthOrder.DAY_FIRST))
    }

    // ------------------------------------------------------------------ dayMonthOrder

    @Test
    fun `a file's dates prove their order by a number only a day can be`() {
        assertEquals(
            DayMonthOrder.DAY_FIRST,
            SourceValues.dayMonthOrder(listOf("02/05/17", null, "2017-05-20", "15/05/17", "nonsense")),
        )
        assertEquals(DayMonthOrder.MONTH_FIRST, SourceValues.dayMonthOrder(listOf("02/05/2015", "02/13/2015")))
    }

    @Test
    fun `a date with a time after it proves its order by its date`() {
        assertEquals(
            DayMonthOrder.DAY_FIRST,
            SourceValues.dayMonthOrder(listOf("02/05/2026 18:30", "15/05/2026 6:30 PM")),
        )
        assertEquals(DayMonthOrder.MONTH_FIRST, SourceValues.dayMonthOrder(listOf(" 5/13/2026 0:00 ")))
    }

    @Test
    fun `dates that never prove an order, or prove both, give none`() {
        assertNull(SourceValues.dayMonthOrder(listOf("02/05/17", "03/05/17")))
        assertNull(SourceValues.dayMonthOrder(listOf("13/05/17", "05/13/17")))
        assertNull(SourceValues.dayMonthOrder(listOf("2017/05/13", "32/05/17", "13/13/17")))
        assertNull(SourceValues.dayMonthOrder(emptyList()))
    }

    // ------------------------------------------------------------------ timeOfDay

    @Test
    fun `a time of day reads on a 24-hour clock or a 12-hour one with its word`() {
        assertEquals(LocalTime.of(18, 4), SourceValues.timeOfDay("18:04"))
        assertEquals(LocalTime.of(7, 0), SourceValues.timeOfDay(" 07:00 "))
        assertEquals(LocalTime.of(22, 56), SourceValues.timeOfDay("10:56 PM"))
        assertEquals(LocalTime.of(22, 56), SourceValues.timeOfDay("10:56\u202FPM"))
        assertEquals(LocalTime.of(18, 4), SourceValues.timeOfDay("6:04 p.m."))
        assertEquals(LocalTime.of(18, 4), SourceValues.timeOfDay("6:04 p. m."))
        assertEquals(LocalTime.of(0, 30), SourceValues.timeOfDay("12:30 AM"))
        assertEquals(LocalTime.of(12, 30), SourceValues.timeOfDay("12:30 pm"))
        assertEquals(LocalTime.of(18, 4), SourceValues.timeOfDay("오후 6:04"))
        assertEquals(LocalTime.of(18, 4), SourceValues.timeOfDay("下午6:04"))
    }

    @Test
    fun `a time of day keeps its seconds when it has them`() {
        assertEquals(LocalTime.of(18, 4, 30), SourceValues.timeOfDay("18:04:30"))
        assertEquals(LocalTime.of(18, 4, 5), SourceValues.timeOfDay("6:04:05 PM"))
    }

    @Test
    fun `anything that is not a time of day is null`() {
        listOf(null, "", "soon", "25:00", "18:60", "13:00 PM", "0:30 AM", "AM 6:04 PM", "6:04 tonight", "18:04:61")
            .forEach { text -> assertNull(text, SourceValues.timeOfDay(text)) }
    }

    // ------------------------------------------------------------------ localDateAt

    @Test
    fun `a date and a time of day apart are the instant they name together, in the zone given`() {
        assertEquals(
            millis("2026-08-11T22:56:00Z"),
            SourceValues.localDateAt("2026/08/11", "10:56 PM", ZoneOffset.UTC),
        )
        assertEquals(
            millis("2017-05-15T22:56:00Z"),
            SourceValues.localDateAt("15/05/17", "18:56", newYork, DayMonthOrder.DAY_FIRST),
        )
        // 02:30 never happened in New York on 10 March 2024.
        assertEquals(millis("2024-03-10T07:30:00Z"), SourceValues.localDateAt("2024-03-10", "2:30 AM", newYork))
    }

    @Test
    fun `a date or a time that does not read is no instant`() {
        assertNull(SourceValues.localDateAt("2026/08/11", null, ZoneOffset.UTC))
        assertNull(SourceValues.localDateAt("2026/08/11", "soon", ZoneOffset.UTC))
        assertNull(SourceValues.localDateAt("15/05/17", "18:56", ZoneOffset.UTC))
        assertNull(SourceValues.localDateAt("+999999999-12-31", "23:59", ZoneOffset.UTC))
    }

    // ------------------------------------------------------------------ localDateTime

    @Test
    fun `a date and a time in one cell are an instant, however a spreadsheet wrote them`() {
        assertEquals(millis("2026-11-02T18:30:00Z"), SourceValues.localDateTime("2026-11-02T18:30", ZoneOffset.UTC))
        assertEquals(millis("2026-11-02T17:30:00Z"), SourceValues.localDateTime("2026-11-02T18:30+01:00", newYork))
        assertEquals(millis("2026-11-02T23:30:15Z"), SourceValues.localDateTime("2026-11-02 18:30:15", newYork))
        assertEquals(millis("2026-11-02T18:30:00Z"), SourceValues.localDateTime("2026/11/02 6:30 PM", ZoneOffset.UTC))
        assertEquals(
            millis("2026-11-02T18:30:00Z"),
            SourceValues.localDateTime(" 02/11/2026 18:30 ", ZoneOffset.UTC, DayMonthOrder.DAY_FIRST),
        )
        assertEquals(
            millis("2026-11-02T18:30:00Z"),
            SourceValues.localDateTime("11/2/2026 6:30 PM", ZoneOffset.UTC, DayMonthOrder.MONTH_FIRST),
        )
    }

    @Test
    fun `a date alone, or a date and a time that do not read, is no instant in one cell`() {
        listOf(null, "", "2026-11-02", "02/11/2026 18:30", "2026-11-02 soon", "yesterday evening").forEach { text ->
            assertNull(text, SourceValues.localDateTime(text, ZoneOffset.UTC))
        }
    }

    // ------------------------------------------------------------------ note

    @Test
    fun `a note's escaped and quoted line breaks all become LF, and the note is trimmed`() {
        assertEquals("Pause the first rep\nElbows tucked", SourceValues.note("Pause the first rep\\nElbows tucked"))
        assertEquals("one\ntwo\nthree", SourceValues.note(" one\r\ntwo\rthree\\n"))
        assertEquals("Slept badly", SourceValues.note("Slept badly"))
    }

    @Test
    fun `a blank note is no note`() {
        listOf(null, "", "   ", "\\n", "\r\n").forEach { text -> assertNull(text, SourceValues.note(text)) }
    }
}
