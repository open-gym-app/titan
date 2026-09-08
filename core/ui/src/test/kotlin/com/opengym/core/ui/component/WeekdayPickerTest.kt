package com.opengym.core.ui.component

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The week as seven cells that are each on or off.
 *
 * Every assertion here addresses the *day* rather than the letter, and that is the component's
 * contract rather than a convenience: `T` stands for Tuesday and Thursday in English and `S`
 * for Saturday and Sunday, so a picker whose only handle is its drawn label is one a screen
 * reader user cannot operate — and one a test cannot address unambiguously either.
 */
@RunWith(RobolectricTestRunner::class)
class WeekdayPickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val toggles = mutableListOf<Pair<DayOfWeek, Boolean>>()

    @Test
    fun `every day is drawn, announcing its full name and its state`() {
        setPicker(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))

        DayOfWeek.entries.forEach { day ->
            val node = composeTestRule.onNodeWithContentDescription(day.name())
            if (day in setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)) {
                node.assertIsOn()
            } else {
                node.assertIsOff()
            }
        }
    }

    @Test
    fun `tapping an off day asks for it to be turned on`() {
        setPicker(setOf(DayOfWeek.MONDAY))

        // Thursday rather than Tuesday on purpose: both draw `T`, and only the description
        // tells them apart.
        composeTestRule.onNodeWithContentDescription(DayOfWeek.THURSDAY.name()).performClick()

        assertEquals(listOf(DayOfWeek.THURSDAY to true), toggles)
    }

    @Test
    fun `the last chosen day can still be cleared`() {
        // Deliberately reachable. The alternative is a control that silently refuses a tap; an
        // empty schedule is a state the screen says something about rather than one it
        // prevents — see `ReminderPreferences.days`.
        setPicker(setOf(DayOfWeek.MONDAY))

        composeTestRule.onNodeWithContentDescription(DayOfWeek.MONDAY.name()).performClick()

        assertEquals(listOf(DayOfWeek.MONDAY to false), toggles)
    }

    @Test
    fun `a disabled picker still says which days are chosen`() {
        setPicker(setOf(DayOfWeek.MONDAY), enabled = false)

        composeTestRule.onNodeWithContentDescription(DayOfWeek.MONDAY.name()).assertIsOn()
        composeTestRule.onNodeWithContentDescription(DayOfWeek.MONDAY.name()).performClick()

        // Readable and inert, the treatment `OpenGymFilterChip` gives: a greyed-out schedule is
        // harder to read than a live one, and what goes is the tap rather than the answer.
        assertEquals(emptyList<Pair<DayOfWeek, Boolean>>(), toggles)
    }

    @Test
    fun `the ambiguous letters really are drawn twice`() {
        // Which is the whole reason the cells carry a description. If this ever stops being
        // true the tests above are stricter than they need to be, and that is worth knowing.
        setPicker(emptySet())

        composeTestRule.onAllNodesWithText("T").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("S").assertCountEquals(2)
    }

    private fun setPicker(
        selected: Set<DayOfWeek>,
        enabled: Boolean = true,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                WeekdayPicker(
                    selected = selected,
                    onToggle = { day, on -> toggles += day to on },
                    enabled = enabled,
                )
            }
        }
    }

    /** The day as the cell announces it. Robolectric pins the locale to en-US. */
    private fun DayOfWeek.name(): String = getDisplayName(TextStyle.FULL, Locale.US)
}
