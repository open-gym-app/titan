package com.titan.feature.history.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.history.sessions.component.DateFilterPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The date sheet's contents, driven directly.
 *
 * The panel rather than `DateFilterSheet`, for the reason [com.titan.feature.history.library.LibraryFilterPanelTest] drives the
 * library's: what is under test is which windows are offered and what choosing one reports,
 * and a `ModalBottomSheet`'s animation and window are neither.
 *
 * Every chip is asserted to be *displayed*, not merely to exist, because the panel wraps
 * rather than scrolling sideways — a version that put these back in a scrolling row would
 * compose two chips and pass an existence check, leaving a user who cannot see "all time" with
 * no way back to it.
 */
@RunWith(RobolectricTestRunner::class)
class DateFilterPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val chosen = mutableListOf<DateFilter>()
    private var doneCount = 0

    @Test
    fun `every window is on screen at once`() {
        setContent()

        composeTestRule.onNodeWithText("ALL TIME").assertIsDisplayed()
        composeTestRule.onNodeWithText("LAST 7 DAYS").assertIsDisplayed()
        composeTestRule.onNodeWithText("LAST 30 DAYS").assertIsDisplayed()
        composeTestRule.onNodeWithText("LAST 90 DAYS").assertIsDisplayed()
        composeTestRule.onNodeWithText("PICK DATES…").assertIsDisplayed()
    }

    @Test
    fun `choosing a rolling window reports it`() {
        setContent()

        composeTestRule.onNodeWithText("LAST 30 DAYS").performClick()

        // The *window*, not the two dates it resolves to today. A resolved pair would stop
        // meaning "the last thirty days" the moment the day rolled over.
        assertEquals(listOf(DateFilter.LastDays(30)), chosen)
    }

    @Test
    fun `the window in force is the chip that is marked`() {
        setContent(DateFilter.LastDays(7))

        composeTestRule.onNodeWithText("LAST 7 DAYS").assertIsSelected()
    }

    @Test
    fun `clearing back to all time reports it`() {
        setContent(DateFilter.LastDays(7))

        composeTestRule.onNodeWithText("ALL TIME").performClick()

        assertEquals(listOf(DateFilter.All), chosen)
    }

    @Test
    fun `a chosen range labels its own chip rather than saying pick dates`() {
        // The chip that opens the picker is also the one that says what came out of it. A
        // separate "custom" chip beside a range nobody can read is two controls for one fact.
        setContent(DateFilter.Between(APRIL_10, APRIL_28))

        val label = "${formatSessionDate(APRIL_10)} – ${formatSessionDate(APRIL_28)}"
        composeTestRule.onNodeWithText(label.uppercase()).assertIsSelected()
        composeTestRule.onNodeWithText("PICK DATES…").assertDoesNotExist()
    }

    @Test
    fun `a single day reads as that day rather than as a range of one`() {
        // What tapping a calendar day leaves behind. `28 Apr – 28 Apr` is the same fact twice.
        setContent(DateFilter.Between(APRIL_28, APRIL_28))

        composeTestRule.onNodeWithText(formatSessionDate(APRIL_28).uppercase()).assertIsSelected()
    }

    @Test
    fun `done closes the sheet without changing the window`() {
        setContent(DateFilter.LastDays(7))

        composeTestRule.onNodeWithText("Done").performClick()

        assertEquals(1, doneCount)
        // The filters applied as they were tapped; there is nothing left for this to confirm.
        assertEquals(emptyList<DateFilter>(), chosen)
    }

    private fun setContent(filter: DateFilter = DateFilter.All) {
        composeTestRule.setContent {
            TitanTheme {
                DateFilterPanel(
                    filter = filter,
                    onFilterSelected = chosen::add,
                    onDone = { doneCount++ },
                )
            }
        }
    }

    private companion object {
        val APRIL_28: LocalDate = LocalDate.of(2026, 4, 28)
        val APRIL_10: LocalDate = LocalDate.of(2026, 4, 10)
    }
}
