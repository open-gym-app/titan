package com.opengym.core.ui.component

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.format.formatTimeOfDay
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.time.Duration.Companion.minutes

/**
 * The three components #24 added to the design system: the session row, the tab strip and the
 * training calendar.
 *
 * One class rather than three, because they are one screen's worth of parts and the
 * assertions worth having are short — what the row *says* as one sentence, that the strip is
 * a group of tabs rather than three buttons, and that only a trained day is tappable.
 *
 * Dates are pinned to a fixed day and the calendar's week to a fixed start, so nothing here
 * depends on when or where it runs.
 */
@RunWith(RobolectricTestRunner::class)
class SessionsComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ─── SessionRow ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a session row announces its name, date and three figures as one sentence`() {
        setRow(name = "Push Day · Upper")

        // One node, not six: a screen reader walking a history list should hear the row and
        // move to the next one. The date is spelled by `formatSessionDate` rather than by a
        // literal here, because how a locale writes one is the JDK's business — what this
        // asserts is that the row says the day at all, between the name and the figures.
        val date = formatSessionDate(LocalDate.of(2026, 4, 28))
        composeTestRule.onNodeWithContentDescription(
            "Push Day · Upper, $date, 52m, 12 sets, 4,820 kg",
        ).assertIsDisplayed()
    }

    @Test
    fun `a session nobody named falls back to the time it started`() {
        // Not "Workout": a lifter who trains twice in a day would get two identical rows, and
        // the date rail beside them says the same day for both.
        setRow(name = null)

        val time = formatTimeOfDay(STARTED_AT)
        composeTestRule.onNodeWithContentDescription("$time, ", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a record puts the badge on the row and says so out loud`() {
        setRow(name = "Legs · Heavy", hasRecord = true)

        // Straight after the name, the way the badge sits beside it, and opening with the
        // letters drawn on the badge: the spoken row keeps the pixels' words in the pixels'
        // order, which is what Accessibility Scanner holds a label to.
        composeTestRule.onNodeWithContentDescription("Legs · Heavy, PR, personal record, ", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a row with nowhere to go takes no click`() {
        composeTestRule.setContent {
            OpenGymTheme {
                SessionRow(
                    name = "Push Day",
                    startedAt = STARTED_AT,
                    duration = 52.minutes,
                    totalSets = 12,
                    totalVolume = VOLUME,
                    onClick = null,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Push Day", substring = true)
            .assertHasNoClickAction()
    }

    @Test
    fun `tapping a row reports it`() {
        var clicked = false
        composeTestRule.setContent {
            OpenGymTheme {
                SessionRow(
                    name = "Push Day",
                    startedAt = STARTED_AT,
                    duration = 52.minutes,
                    totalSets = 12,
                    totalVolume = VOLUME,
                    onClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Push Day", substring = true).performClick()

        assertTrue(clicked)
    }

    // ─── OpenGymTabRow ──────────────────────────────────────────────────────────────────

    @Test
    fun `the tab strip marks one tab selected and reports the others`() {
        val selected = mutableListOf<Int>()
        composeTestRule.setContent {
            OpenGymTheme {
                OpenGymTabRow(
                    labels = listOf("History", "Templates", "Calendar"),
                    selectedIndex = 0,
                    onSelect = selected::add,
                )
            }
        }

        composeTestRule.onNodeWithText("HISTORY").assertIsSelected()
        composeTestRule.onNodeWithText("CALENDAR").assertIsNotSelected()
        composeTestRule.onNodeWithText("CALENDAR").performClick()

        assertEquals(listOf(2), selected)
    }

    @Test
    fun `an out-of-range selection selects nothing rather than throwing`() {
        // What a saved index restored after the tabs changed looks like. A crash here would
        // be a process restore that never completes.
        composeTestRule.setContent {
            OpenGymTheme {
                OpenGymTabRow(
                    labels = listOf("History", "Templates"),
                    selectedIndex = 7,
                    onSelect = {},
                )
            }
        }

        composeTestRule.onNodeWithText("HISTORY").assertIsNotSelected()
    }

    // ─── TrainingCalendar ───────────────────────────────────────────────────────────────

    @Test
    fun `a trained day announces its count and opens, and a rest day does neither`() {
        composeTestRule.setContent {
            OpenGymTheme {
                TrainingCalendar(
                    month = MONTH,
                    days = mapOf(28 to TrainingDay(sessionCount = 2, load = TrainingLoad.HEAVY)),
                    weekStart = DayOfWeek.MONDAY,
                    onDayClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(day(28) + ", 2 sessions")
            .assertHasClickAction()
        // A rest day has nothing to filter a list down to, so it does not ripple.
        composeTestRule.onNodeWithContentDescription(day(29) + ", rest day")
            .assertHasNoClickAction()
    }

    @Test
    fun `tapping a trained day reports its date`() {
        val tapped = mutableListOf<LocalDate>()
        composeTestRule.setContent {
            OpenGymTheme {
                TrainingCalendar(
                    month = MONTH,
                    days = mapOf(28 to TrainingDay(sessionCount = 1, load = TrainingLoad.LIGHT)),
                    weekStart = DayOfWeek.MONDAY,
                    onDayClick = tapped::add,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(day(28) + ", 1 session").performClick()

        assertEquals(listOf(LocalDate.of(2026, 4, 28)), tapped)
    }

    @Test
    fun `the grid holds exactly the month's days`() {
        composeTestRule.setContent {
            OpenGymTheme {
                TrainingCalendar(month = MONTH, days = emptyMap(), weekStart = DayOfWeek.MONDAY)
            }
        }

        // April has thirty. A grid that ran on into May would shade a day twice on two pages,
        // which is a session counted twice to anybody reading it.
        composeTestRule.onNodeWithContentDescription(day(30) + ", rest day").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(
            formatSessionDate(LocalDate.of(2026, 5, 1)) + ", rest day",
        ).assertDoesNotExist()
    }

    /** A day of the fixture's month, spelled the way the calendar cell spells it. */
    private fun day(dayOfMonth: Int): String = formatSessionDate(MONTH.atDay(dayOfMonth))

    private fun setRow(
        name: String?,
        hasRecord: Boolean = false,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                SessionRow(
                    name = name,
                    startedAt = STARTED_AT,
                    duration = 52.minutes,
                    totalSets = 12,
                    totalVolume = VOLUME,
                    hasRecord = hasRecord,
                    onClick = {},
                )
            }
        }
    }

    private companion object {
        /** 2026-04-28, midday UTC — far enough from midnight to be the 28th in every zone. */
        const val STARTED_AT = 1_777_377_600_000L
        const val VOLUME = 4_820_000_000L

        val MONTH: YearMonth = YearMonth.of(2026, 4)
    }
}
