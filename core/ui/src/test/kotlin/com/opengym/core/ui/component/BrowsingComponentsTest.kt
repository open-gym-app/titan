package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The three components a browsing screen is assembled from — the search field, the filter
 * chip and the empty state — and the personal-record figure that ends a row.
 *
 * One class rather than four, because they are one *set*: the library, the session list and
 * the template picker all use the same four, and a change that breaks one of them is a change
 * to how browsing works everywhere.
 */
@RunWith(RobolectricTestRunner::class)
class BrowsingComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent { OpenGymTheme { Column { content() } } }
    }

    // ─── The search field ───────────────────────────────────────────────────────────────

    @Test
    fun `an empty field shows its placeholder and no way to clear it`() {
        setContent { OpenGymSearchField(query = "", onQueryChange = {}) }

        composeTestRule.onNodeWithText("Search…").assertIsDisplayed()
        // A clear button on an empty field is a control that does nothing, and reads as one.
        composeTestRule.onNodeWithContentDescription("Clear the search").assertDoesNotExist()
    }

    @Test
    fun `typing reports every keystroke to the caller`() {
        val typed = mutableListOf<String>()
        setContent { OpenGymSearchField(query = "", onQueryChange = typed::add) }

        composeTestRule.onNodeWithText("Search…").performTextInput("bench")

        assertEquals(listOf("bench"), typed)
    }

    @Test
    fun `the clear button empties the query rather than clearing anything itself`() {
        val typed = mutableListOf<String>()
        setContent { OpenGymSearchField(query = "bench", onQueryChange = typed::add) }

        composeTestRule.onNodeWithContentDescription("Clear the search").performClick()

        // The field holds nothing of its own — clearing is a value reported upwards.
        assertEquals(listOf(""), typed)
    }

    @Test
    fun `a field with a query shows it instead of the placeholder`() {
        setContent { OpenGymSearchField(query = "bench", onQueryChange = {}) }

        composeTestRule.onNodeWithText("bench").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search…").assertDoesNotExist()
    }

    // ─── The filter chip ────────────────────────────────────────────────────────────────

    @Test
    fun `a chip shouts its label and reports its taps`() {
        var clicks = 0
        setContent { OpenGymFilterChip(label = "Chest", selected = false, onClick = { clicks++ }) }

        composeTestRule.onNodeWithText("CHEST").assertIsDisplayed()
        composeTestRule.onNodeWithText("CHEST").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `a chip announces whether it is on`() {
        setContent {
            OpenGymFilterChip(label = "Chest", selected = true, onClick = {})
            OpenGymFilterChip(label = "Back", selected = false, onClick = {})
        }

        composeTestRule.onNodeWithText("CHEST").assertIsSelected()
        composeTestRule.onNodeWithText("BACK").assertIsNotSelected()
    }

    // ─── The empty state ────────────────────────────────────────────────────────────────

    @Test
    fun `an empty state with an action offers it`() {
        var cleared = 0
        setContent {
            EmptyState(
                glyph = "⌕",
                title = "Nothing matches",
                description = "Try fewer filters.",
                actionLabel = "Clear filters",
                action = { cleared++ },
            )
        }

        composeTestRule.onNodeWithText("Nothing matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("Try fewer filters.").assertIsDisplayed()
        composeTestRule.onNodeWithText("CLEAR FILTERS").performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun `an empty state with nothing to do about it draws no button`() {
        setContent { EmptyState(title = "Nothing logged yet") }

        composeTestRule.onNodeWithText("Nothing logged yet").assertIsDisplayed()
        // An action label with no action is not half a button; it is no button.
        composeTestRule.onNodeWithText("CLEAR FILTERS").assertDoesNotExist()
    }

    // ─── The record figure ──────────────────────────────────────────────────────────────

    @Test
    fun `a weight record is announced as one phrase, not as a tag and a number`() {
        setContent { PrValue(record = record(PrType.MAX_WEIGHT, weight = 92_000_000)) }

        composeTestRule
            .onNodeWithContentDescription("Personal record, Heaviest, 92 kg")
            .assertIsDisplayed()
    }

    @Test
    fun `a rep record counts its reps`() {
        setContent { PrValue(record = record(PrType.MAX_REPS, reps = 45)) }

        composeTestRule
            .onNodeWithContentDescription("Personal record, Most reps, 45 reps")
            .assertIsDisplayed()
    }

    @Test
    fun `a record missing the figure its own type is about draws nothing`() {
        setContent {
            PrValue(record = record(PrType.MAX_WEIGHT))
            EmptyState(title = "Marker")
        }

        // Not a `0 kg` record the user never set: the row simply has no figure on it.
        composeTestRule.onNodeWithText("Marker").assertIsDisplayed()
        composeTestRule.onNodeWithText("PR").assertDoesNotExist()
    }

    @Test
    fun `every record type either draws a figure or draws nothing`() {
        val rendered = mutableMapOf<PrType, String?>()
        composeTestRule.setContent {
            OpenGymTheme {
                PrType.entries.forEach { type ->
                    rendered[type] = record(
                        type = type,
                        weight = 92_000_000,
                        reps = 45,
                        distance = 5_000_00,
                        duration = 1_800,
                    ).formattedValue()
                }
            }
        }

        assertEquals(PrType.entries.size, rendered.size)
        assertTrue(rendered.values.all { !it.isNullOrBlank() })
    }

    private fun record(
        type: PrType,
        weight: Long? = null,
        reps: Int? = null,
        distance: Long? = null,
        duration: Long? = null,
    ) = PersonalRecord(
        id = 1,
        exerciseId = 1,
        workoutSetId = 1,
        type = type,
        weight = weight,
        reps = reps,
        distance = distance,
        duration = duration,
        estimated1Rm = weight,
        achievedAt = 0,
    )
}
