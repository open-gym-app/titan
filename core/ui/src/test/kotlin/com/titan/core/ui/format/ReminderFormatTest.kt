package com.titan.core.ui.format

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.titan.core.domain.notification.ReminderPreferences
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

/**
 * A reminder schedule as one line: `MWF · 18:00`.
 *
 * Two screens draw this — the settings row and the reminder screen's header — so what is being
 * pinned is that there is one answer rather than two. The three named cases are the ones where
 * the initials fail: `MTWTFSS` is a smear, and nobody reads `MTWTF` as "weekdays".
 *
 * The composable half runs under Robolectric because it resolves strings; the pure half
 * ([reminderDayInitials], [is24HourClock]) is asserted directly, which is also how the locale
 * cases are reached without restarting a composition.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderFormatTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- the initials -------------------------------------------------------

    @Test
    fun `a scattered schedule is drawn as its initials`() {
        assertEquals("MWF", initialsOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
    }

    @Test
    fun `the initials are in the week's order, whatever order the set is in`() {
        // A `Set` carries no order, so every reader would otherwise pick its own.
        assertEquals("MWF", initialsOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun `the week starts where the locale starts it`() {
        val across = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)

        // The United States opens the week on Sunday and Britain on Monday, so the same two
        // days read in the opposite order. Both are the days the user picked; neither starts
        // the week in the middle.
        assertEquals("SM", reminderDayInitials(across, Locale.US))
        assertEquals("MS", reminderDayInitials(across, Locale.UK))
    }

    // --- the named cases ----------------------------------------------------

    @Test
    fun `all seven days are a phrase rather than seven letters`() {
        assertRendered(ReminderPreferences(days = DayOfWeek.entries.toSet()), "Every day · 6:00 PM")
    }

    @Test
    fun `monday to friday is weekdays`() {
        val weekdays = DayOfWeek.entries.toSet() - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY

        assertRendered(ReminderPreferences(days = weekdays), "Weekdays · 6:00 PM")
    }

    @Test
    fun `saturday and sunday are weekends`() {
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        assertRendered(ReminderPreferences(days = weekend), "Weekends · 6:00 PM")
    }

    @Test
    fun `four of the five weekdays is not weekdays`() {
        // The phrase names a set, not a size. `MTWT` is short enough to read as itself.
        val fourDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)

        assertRendered(ReminderPreferences(days = fourDays), "MTWT · 6:00 PM")
    }

    // --- the whole line -----------------------------------------------------

    @Test
    fun `the schedule joins the days and the time`() {
        assertRendered(ReminderPreferences(), "MWF · 6:00 PM")
    }

    @Test
    fun `a schedule with no days drops the time it would not fire at`() {
        // `No days · 18:00` states a time nothing is going to happen at.
        assertRendered(ReminderPreferences(days = emptySet()), "No days")
    }

    // --- the clock the time is written on -----------------------------------

    @Test
    fun `whether the clock runs to twelve is read from the locale's own pattern`() {
        // The same pattern `formatTimeOfDay` renders through, so a control that *sets* a time
        // and the row that reads it back cannot disagree about the meridiem.
        assertFalse(is24HourClock(Locale.US))
        assertTrue(is24HourClock(Locale.UK))
        assertTrue(is24HourClock(Locale.FRANCE))
    }

    @Test
    fun `a bare time renders on that clock`() {
        assertEquals("6:00 PM", formatTimeOfDay(LocalTime.of(18, 0), Locale.US))
        assertEquals("18:00", formatTimeOfDay(LocalTime.of(18, 0), Locale.UK))
    }

    private fun initialsOf(vararg days: DayOfWeek): String = reminderDayInitials(days.toSet(), Locale.US)

    /** Renders [reminders] through the composable formatter and asserts the line it produces. */
    private fun assertRendered(
        reminders: ReminderPreferences,
        expected: String,
    ) {
        composeTestRule.setContent {
            TitanTheme { Text(text = formatReminderSchedule(reminders)) }
        }

        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
    }
}
