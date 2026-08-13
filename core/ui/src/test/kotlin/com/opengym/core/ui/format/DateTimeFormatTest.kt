package com.opengym.core.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * Dates and clock times, pinned to a zone and a locale.
 *
 * Both are parameters precisely so these assertions are about the formatter rather than about
 * the machine running them — a test that read `ZoneId.systemDefault()` would pass in Paris and
 * fail in CI.
 *
 * The exact glyphs a locale produces are the JDK's business and are deliberately not asserted
 * beyond the parts this code puts there. What *is* asserted is what the app depends on: the
 * instant lands on the right day in the right zone, the weekday is in front of it, and both
 * follow the locale they are handed rather than the default.
 */
class DateTimeFormatTest {

    // 2026-04-28T09:14:00Z — a Tuesday morning UTC, which is still Monday evening in Honolulu.
    private val instant = 1_777_367_640_000L
    private val utc = ZoneId.of("UTC")
    private val uk = Locale.UK

    @Test
    fun `a session's day carries its weekday in front of the date`() {
        val formatted = formatSessionDate(instant, zone = utc, locale = uk)

        assertTrue(formatted, formatted.startsWith("Tue"))
        assertTrue(formatted, formatted.contains("28"))
        assertTrue(formatted, formatted.contains("Apr"))
        assertTrue(formatted, formatted.contains("2026"))
    }

    @Test
    fun `the instant is read in the zone it is handed, not in the machine's`() {
        // The same moment is Monday in Honolulu and Tuesday in London. A lifter who flies
        // should see the workout on the day they did it, which is what makes the zone a
        // parameter rather than a constant.
        val london = formatSessionDate(instant, zone = ZoneId.of("Europe/London"), locale = uk)
        val honolulu = formatSessionDate(instant, zone = ZoneId.of("Pacific/Honolulu"), locale = uk)

        assertTrue(london, london.startsWith("Tue"))
        assertTrue(honolulu, honolulu.startsWith("Mon"))
    }

    @Test
    fun `the date follows the locale it is handed`() {
        assertNotEquals(
            formatSessionDate(instant, zone = utc, locale = uk),
            formatSessionDate(instant, zone = utc, locale = Locale.FRANCE),
        )
    }

    @Test
    fun `a time of day is the clock reading, to the minute`() {
        // Short style: no seconds. The length of a session is a duration, and `formatAsClock`
        // is the only thing on the summary precise to the second.
        assertEquals("09:14", formatTimeOfDay(instant, zone = utc, locale = uk))
    }

    @Test
    fun `a twelve-hour locale gets its meridiem`() {
        val formatted = formatTimeOfDay(instant, zone = utc, locale = Locale.US)

        assertTrue(formatted, formatted.startsWith("9:14"))
        // The separator between the time and the marker is a narrow no-break space in newer
        // JDKs and an ordinary one in older ones, so the marker is matched rather than the
        // whole string.
        assertTrue(formatted, formatted.contains("AM"))
    }

    @Test
    fun `a time of day is read in the zone it is handed`() {
        assertEquals("11:14", formatTimeOfDay(instant, zone = ZoneId.of("Europe/Paris"), locale = uk))
    }
}
