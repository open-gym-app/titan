package com.opengym.core.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * When the next reminder is due.
 *
 * The arithmetic is the whole of this class and it is the half of #39 that is not the platform's
 * — an alarm set for the wrong instant is indistinguishable from an alarm that was never set, so
 * these pin the boundary cases the scheduler is built on: today after the time, today *at* it,
 * and the two days a year an hour appears or disappears underneath the schedule.
 */
class ReminderPreferencesTest {

    // --- the next occurrence ------------------------------------------------

    @Test
    fun `a day later this week is this week`() {
        // Monday 09:00, asking about Wednesday.
        val next = MWF_AT_SIX.nextOccurrence(DayOfWeek.WEDNESDAY, at(MONDAY, 9, 0), PARIS)

        assertEquals(at(MONDAY.plusDays(2), 18, 0), next)
    }

    @Test
    fun `today counts, while the time is still ahead`() {
        val next = MWF_AT_SIX.nextOccurrence(DayOfWeek.MONDAY, at(MONDAY, 9, 0), PARIS)

        assertEquals(at(MONDAY, 18, 0), next)
    }

    @Test
    fun `today has gone once the time has passed`() {
        val next = MWF_AT_SIX.nextOccurrence(DayOfWeek.MONDAY, at(MONDAY, 18, 1), PARIS)

        assertEquals(at(MONDAY.plusWeeks(1), 18, 0), next)
    }

    @Test
    fun `the occurrence being asked from is not the answer to itself`() {
        // The case the re-arm actually hits: the alarm has just fired *at* 18:00 and is
        // scheduling the next one. Answering "18:00 today" would set an alarm for the instant
        // it is being set at, which the system may fire immediately — and then again, forever.
        val next = MWF_AT_SIX.nextOccurrence(DayOfWeek.MONDAY, at(MONDAY, 18, 0), PARIS)

        assertEquals(at(MONDAY.plusWeeks(1), 18, 0), next)
    }

    @Test
    fun `a day need not be one of the chosen ones`() {
        // The scheduler asks about whichever days it is arming; deciding which those are is
        // its business rather than this function's.
        val next = MWF_AT_SIX.nextOccurrence(DayOfWeek.SUNDAY, at(MONDAY, 9, 0), PARIS)

        assertEquals(at(MONDAY.plusDays(6), 18, 0), next)
    }

    // --- daylight saving ----------------------------------------------------

    @Test
    fun `a reminder in the hour that spring forward deletes still fires that day`() {
        // Paris skips 02:00-03:00 on 30 March 2025, so a 02:30 reminder is a wall-clock time
        // that does not exist that Sunday. The reminder is still due, and the answer is the
        // first moment the clock reaches it: 03:30. The failure this rules out is the one that
        // matters — a reminder silently skipped on the day the hour vanished.
        val reminders = ReminderPreferences(days = setOf(DayOfWeek.SUNDAY), timeOfDay = LocalTime.of(2, 30))

        val next = reminders.nextOccurrence(DayOfWeek.SUNDAY, at(LocalDate.of(2025, 3, 29), 9, 0), PARIS)

        assertEquals(LocalDateTime.of(2025, 3, 30, 3, 30), localTimeOf(next))
    }

    @Test
    fun `a reminder in the hour that autumn repeats fires on the day, once`() {
        // Paris repeats 02:00-03:00 on 26 October 2025, so 02:30 happens twice. Which of the
        // two offsets is taken is `ZonedDateTime`'s business and not worth pinning; what is
        // worth pinning is that the reminder lands on the day, at the wall-clock time asked
        // for, and that one alarm is scheduled rather than two.
        val reminders = ReminderPreferences(days = setOf(DayOfWeek.SUNDAY), timeOfDay = LocalTime.of(2, 30))

        val occurrences = reminders.nextOccurrences(at(LocalDate.of(2025, 10, 25), 9, 0), PARIS)

        assertEquals(1, occurrences.size)
        assertEquals(LocalDateTime.of(2025, 10, 26, 2, 30), localTimeOf(occurrences.values.single()))
    }

    @Test
    fun `the schedule is wall clock, so it does not move with the zone`() {
        // The same Monday 09:00 instant, read in two zones. 18:00 local is 18:00 local, which
        // is the whole reason a schedule is a `LocalTime` and a set of weekdays.
        val from = at(MONDAY, 9, 0)

        val paris = MWF_AT_SIX.nextOccurrence(DayOfWeek.MONDAY, from, PARIS)
        val newYork = MWF_AT_SIX.nextOccurrence(DayOfWeek.MONDAY, from, NEW_YORK)

        assertTrue("New York's 18:00 comes six hours after Paris'", newYork > paris)
    }

    // --- the whole schedule -------------------------------------------------

    @Test
    fun `every chosen day gets an occurrence, keyed by the day it is for`() {
        val occurrences = MWF_AT_SIX.nextOccurrences(at(MONDAY, 9, 0), PARIS)

        assertEquals(MWF_AT_SIX.days, occurrences.keys)
        assertEquals(at(MONDAY, 18, 0), occurrences[DayOfWeek.MONDAY])
        assertEquals(at(MONDAY.plusDays(2), 18, 0), occurrences[DayOfWeek.WEDNESDAY])
        assertEquals(at(MONDAY.plusDays(4), 18, 0), occurrences[DayOfWeek.FRIDAY])
    }

    @Test
    fun `the soonest of them is what the screen names`() {
        assertEquals(at(MONDAY, 18, 0), MWF_AT_SIX.nextOccurrenceOrNull(at(MONDAY, 9, 0), PARIS))
    }

    @Test
    fun `a schedule with no days has no next occurrence and nothing to schedule`() {
        val empty = ReminderPreferences(days = emptySet())

        assertFalse(empty.isSchedulable)
        assertEquals(emptyMap<DayOfWeek, Long>(), empty.nextOccurrences(at(MONDAY, 9, 0), PARIS))
        assertNull(empty.nextOccurrenceOrNull(at(MONDAY, 9, 0), PARIS))
    }

    // --- ordering and validation --------------------------------------------

    @Test
    fun `the days come back in the week's order, from the day the week starts on`() {
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            MWF_AT_SIX.daysFrom(DayOfWeek.MONDAY),
        )
        // The same three days read from an American week, where the week starts on Sunday.
        assertEquals(
            listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            MWF_AT_SIX.daysFrom(DayOfWeek.SUNDAY),
        )
        // A set spanning the weekend is where the starting day actually changes the answer.
        val weekend = ReminderPreferences(days = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY))
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), weekend.daysFrom(DayOfWeek.MONDAY))
        assertEquals(listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), weekend.daysFrom(DayOfWeek.SUNDAY))
    }

    @Test
    fun `a reminder cannot be set finer than the minute`() {
        // The picker offers minutes and the settings row reads back minutes, so a stored
        // 18:00:30 would be a schedule the UI can neither express nor display.
        assertThrows(IllegalArgumentException::class.java) {
            ReminderPreferences(timeOfDay = LocalTime.of(18, 0, 30))
        }
    }

    @Test
    fun `the defaults are the wireframe's own schedule`() {
        val defaults = ReminderPreferences()

        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), defaults.days)
        assertEquals(LocalTime.of(18, 0), defaults.timeOfDay)
        assertTrue(defaults.isSchedulable)
    }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

        /** An ordinary Monday, well clear of either daylight-saving boundary. */
        val MONDAY: LocalDate = LocalDate.of(2026, 4, 27)

        val MWF_AT_SIX = ReminderPreferences()

        /** [date] at [hour]:[minute] in Paris, as epoch millis. */
        fun at(
            date: LocalDate,
            hour: Int,
            minute: Int,
        ): Long = instant(LocalDateTime.of(date, LocalTime.of(hour, minute)))

        /** A Paris wall-clock moment as epoch millis. */
        fun instant(at: LocalDateTime): Long = at.atZone(PARIS).toInstant().toEpochMilli()

        /** The other direction: what a Paris clock reads at [epochMillis]. */
        fun localTimeOf(epochMillis: Long): LocalDateTime =
            Instant.ofEpochMilli(epochMillis).atZone(PARIS).toLocalDateTime()
    }
}
