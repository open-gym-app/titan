package com.opengym.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.SetType
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The set row, driven stateless. The fields hold no state of their own — the test supplies
 * the text and asserts on the change the row reports, which is the whole contract.
 *
 * Cells are found by their content description rather than by their contents: a weight field
 * and a rep field both show digits, and "the node whose text is 80" stops being unambiguous
 * the moment a set is 80 reps.
 */
@RunWith(RobolectricTestRunner::class)
class SetLogRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val weightEdits = mutableListOf<String>()
    private val repEdits = mutableListOf<String>()
    private val completions = mutableListOf<Boolean>()

    @Test
    fun `renders the values it is given`() {
        setRow(weight = "80", reps = "5", rpe = 85, previous = "75 × 6")

        composeTestRule.onNodeWithText("80").assertIsDisplayed()
        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        composeTestRule.onNodeWithText("8.5").assertIsDisplayed()
    }

    @Test
    fun `the previous column is announced as a sentence rather than as four characters`() {
        setRow(weight = "80", reps = "5", previous = "75 × 6")

        composeTestRule.onNodeWithContentDescription("Previously 75 × 6").assertIsDisplayed()
    }

    @Test
    fun `reports what was typed into the weight field`() {
        setRow(weight = "80", reps = "5")

        composeTestRule.onNodeWithContentDescription("Weight, set 3").performTextReplacement("82.5")

        assertEquals(listOf("82.5"), weightEdits)
        assertEquals(emptyList<String>(), repEdits)
    }

    @Test
    fun `reports what was typed into the rep field`() {
        setRow(weight = "80", reps = "5")

        composeTestRule.onNodeWithContentDescription("Reps, set 3").performTextReplacement("6")

        assertEquals(listOf("6"), repEdits)
        assertEquals(emptyList<String>(), weightEdits)
    }

    @Test
    fun `the tick reports the state it is being moved to`() {
        setRow(weight = "80", reps = "5")

        composeTestRule.onNodeWithContentDescription("Mark set 3 done")
            .assertIsOff()
            .performClick()

        assertEquals(listOf(true), completions)
    }

    @Test
    fun `a completed row offers to reopen the set rather than to complete it`() {
        setRow(weight = "80", reps = "5", isCompleted = true)

        composeTestRule.onNodeWithContentDescription("Set 3 is done. Reopen it")
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun `an invalid row shows the reason it was refused`() {
        setRow(weight = "80", reps = "0", error = "reps must be within 1..10,000, was 0")

        composeTestRule.onNodeWithText("reps must be within 1..10,000, was 0").assertIsDisplayed()
    }

    @Test
    fun `negative - a valid row shows no error text`() {
        setRow(weight = "80", reps = "5")

        composeTestRule.onNodeWithText("reps must be within 1..10,000, was 0").assertDoesNotExist()
    }

    @Test
    fun `a set type replaces the number with its marker and says so to a screen reader`() {
        setRow(weight = "60", reps = "12", setType = SetType.WARMUP)

        composeTestRule.onNodeWithContentDescription("Warm-up, set 3").assertIsDisplayed()
    }

    @Test
    fun `a normal set keeps its number`() {
        setRow(weight = "60", reps = "12")

        composeTestRule.onNodeWithContentDescription("Working set, set 3").assertExists()
    }

    @Test
    fun `a disabled row cannot be ticked`() {
        setRow(weight = "80", reps = "5", enabled = false)

        composeTestRule.onNodeWithContentDescription("Mark set 3 done").assertIsNotEnabled()
        assertEquals(emptyList<Boolean>(), completions)
    }

    @Test
    fun `a logged set is formatted from the domain values, not typed`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(
                    setNumber = 1,
                    set = ActiveSet(
                        reps = 10,
                        weight = 60_000_000,
                        rpe = 60,
                        isCompleted = true,
                        completedAt = 1,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("60 kg × 10").assertIsDisplayed()
        composeTestRule.onNodeWithText("6").assertIsDisplayed()
    }

    @Test
    fun `a logged set with no weight reads as reps alone`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(setNumber = 1, set = ActiveSet(reps = 12, isCompleted = true, completedAt = 1))
            }
        }

        composeTestRule.onNodeWithText("12 reps").assertIsDisplayed()
    }

    private fun setRow(
        weight: String,
        reps: String,
        setType: SetType = SetType.NORMAL,
        rpe: Int? = null,
        previous: String? = null,
        isCompleted: Boolean = false,
        error: String? = null,
        enabled: Boolean = true,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                SetLogRow(
                    setNumber = 3,
                    weight = weight,
                    reps = reps,
                    onWeightChange = weightEdits::add,
                    onRepsChange = repEdits::add,
                    onCompletedChange = completions::add,
                    setType = setType,
                    rpe = rpe,
                    previous = previous,
                    isCompleted = isCompleted,
                    error = error,
                    enabled = enabled,
                )
            }
        }
    }
}
