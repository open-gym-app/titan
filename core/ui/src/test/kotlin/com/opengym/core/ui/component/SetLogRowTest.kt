package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.SetType
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The set row, driven stateless. The fields hold no state of their own — the test supplies
 * the text and asserts on the change the row reports, which is the whole contract.
 *
 * Cells are found by their content description rather than by their contents: a weight field
 * and a rep field both show digits, and "the node whose text is 80" stops being unambiguous
 * the moment a set is 80 reps.
 *
 * A phone-sized viewport rather than Robolectric's 320 × 470 default: two cells of this row open
 * menus, and on a screen no phone has shipped with this decade the bottom of the rating scale is
 * off the display before the test has touched anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SetLogRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val weightEdits = mutableListOf<String>()
    private val repEdits = mutableListOf<String>()
    private val completions = mutableListOf<Boolean>()
    private val types = mutableListOf<SetType>()
    private val ratings = mutableListOf<Int?>()

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

        composeTestRule.onNodeWithContentDescription("Set 3 is done. Un-tick it")
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun `a set with no history keeps the column, so the rows of one table line up`() {
        composeTestRule.setContent {
            OpenGymTheme {
                Column {
                    SetLogRow(
                        setNumber = 1,
                        weight = "80",
                        reps = "5",
                        onWeightChange = {},
                        onRepsChange = {},
                        onCompletedChange = {},
                        previous = "75 × 6",
                    )
                    SetLogRow(
                        setNumber = 2,
                        weight = "80",
                        reps = "5",
                        onWeightChange = {},
                        onRepsChange = {},
                        onCompletedChange = {},
                        previous = null,
                    )
                }
            }
        }

        val withHistory = composeTestRule.onNodeWithContentDescription("Weight, set 1")
            .getUnclippedBoundsInRoot().left
        val without = composeTestRule.onNodeWithContentDescription("Weight, set 2")
            .getUnclippedBoundsInRoot().left

        assertEquals(withHistory.value, without.value, HALF_A_PIXEL)
    }

    @Test
    fun `a table with no history anywhere drops the column instead of reserving it`() {
        composeTestRule.setContent {
            OpenGymTheme {
                Column {
                    SetLogRow(
                        setNumber = 1,
                        weight = "80",
                        reps = "5",
                        onWeightChange = {},
                        onRepsChange = {},
                        onCompletedChange = {},
                        previous = null,
                        showPrevious = true,
                    )
                    SetLogRow(
                        setNumber = 2,
                        weight = "80",
                        reps = "5",
                        onWeightChange = {},
                        onRepsChange = {},
                        onCompletedChange = {},
                        previous = null,
                        showPrevious = false,
                    )
                }
            }
        }

        val reserved = composeTestRule.onNodeWithContentDescription("Weight, set 1")
            .getUnclippedBoundsInRoot().left
        val dropped = composeTestRule.onNodeWithContentDescription("Weight, set 2")
            .getUnclippedBoundsInRoot().left

        assertTrue(
            "dropping the column must give its width back to the fields, not hold it empty",
            dropped.value < reserved.value,
        )
    }

    @Test
    fun `an invalid row shows the reason it was refused`() {
        setRow(weight = "80", reps = "0", error = "reps must be within 1..10,000, was 0")

        composeTestRule.onNodeWithText("reps must be within 1..10,000, was 0").assertIsDisplayed()
    }

    @Test
    fun `the refusal is announced on the fields, which are what a screen reader stops on`() {
        setRow(weight = "80", reps = "0", error = "reps must be within 1..10,000, was 0")

        // The row's frame is a Column with no text and no role: TalkBack never lands on it,
        // so an error announced there is an error nobody hears.
        composeTestRule.onNodeWithContentDescription("Reps, set 3")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "reps must be within 1..10,000, was 0"))
        composeTestRule.onNodeWithContentDescription("Weight, set 3")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `negative - a valid row puts no error on its fields`() {
        setRow(weight = "80", reps = "5")

        composeTestRule.onNodeWithContentDescription("Reps, set 3")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun `the RPE cell announces the value, not just the name of the column`() {
        setRow(weight = "80", reps = "5", rpe = 85)

        // A content description replaces the text it is set on. Labelling the cell and
        // leaving the number to the text means the number is never read.
        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 3, 8.5")
            .assertIsDisplayed()
    }

    @Test
    fun `an RPE that was never given says so rather than reading out a dash`() {
        setRow(weight = "80", reps = "5", rpe = null)

        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 3, not set")
            .assertIsDisplayed()
    }

    @Test
    fun `negative - the multiplication sign is not a stop of its own`() {
        setRow(weight = "80", reps = "5")

        // Five sets would otherwise be five announcements of "multiplication sign" between
        // the values it is punctuating.
        composeTestRule.onNodeWithText("×").assertDoesNotExist()
    }

    @Test
    fun `negative - a valid row shows no error text`() {
        setRow(weight = "80", reps = "5")

        composeTestRule.onNodeWithText("reps must be within 1..10,000, was 0").assertDoesNotExist()
    }

    @Test
    fun `the leading cell announces the type it is showing, and which set it is`() {
        setRow(weight = "60", reps = "12", setType = SetType.WARMUP)

        // The cell draws one letter. A screen reader gets the word, and the number the letter
        // replaced — neither of which is recoverable from a `W`.
        composeTestRule.onNodeWithContentDescription("Set type, set 3, Warm-up").assertIsDisplayed()
    }

    @Test
    fun `the set type is picked from the cell that shows it`() {
        setRow(weight = "60", reps = "12")

        composeTestRule.onNodeWithContentDescription("Set type, set 3, Standard set").performClick()
        composeTestRule.onNodeWithText("Warm-up").performClick()

        assertEquals(listOf(SetType.WARMUP), types)
    }

    @Test
    fun `the rating is picked from the cell that shows it, off a closed scale`() {
        setRow(weight = "80", reps = "5", rpe = 75)

        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 3, 7.5")
            .performClick()
        composeTestRule.onNodeWithContentDescription("Rate of perceived exertion 9").performClick()

        assertEquals(listOf(90), ratings)
    }

    @Test
    fun `the rating menu's way out is what clears a rating`() {
        setRow(weight = "80", reps = "5", rpe = 75)

        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 3, 7.5")
            .performClick()
        composeTestRule.onNodeWithText("Not rated").performClick()

        assertEquals(listOf<Int?>(null), ratings)
    }

    @Test
    fun `a disabled row cannot be ticked`() {
        setRow(weight = "80", reps = "5", enabled = false)

        composeTestRule.onNodeWithContentDescription("Mark set 3 done").assertIsNotEnabled()
        assertEquals(emptyList<Boolean>(), completions)
    }

    @Test
    fun `negative - a disabled row's menus do not open`() {
        setRow(weight = "80", reps = "5", rpe = 75, enabled = false)

        composeTestRule.onNodeWithContentDescription("Set type, set 3, Standard set").performClick()
        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 3, 7.5")
            .performClick()

        composeTestRule.onNodeWithText("Warm-up").assertDoesNotExist()
        composeTestRule.onNodeWithText("Not rated").assertDoesNotExist()
        assertEquals(emptyList<SetType>(), types)
        assertEquals(emptyList<Int?>(), ratings)
    }

    @Test
    fun `a row that does not hold a set yet keeps its fields and loses its tick`() {
        // The narrower half of `enabled`: nothing about the row is read-only, it simply is not
        // a set until the dimension its exercise is logged in has been filled in — and a tick
        // that was live for it would be pressed for a set the write path refuses.
        setRow(weight = "80", reps = "", canComplete = false)

        composeTestRule.onNodeWithContentDescription("Mark set 3 done").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Weight, set 3").performTextReplacement("82.5")

        assertEquals(listOf("82.5"), weightEdits)
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
    fun `a logged set's leading cell is a label rather than a control`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(
                    setNumber = 1,
                    set = ActiveSet(
                        reps = 10,
                        weight = 60_000_000,
                        setType = SetType.WARMUP,
                        isCompleted = true,
                        completedAt = 1,
                    ),
                )
            }
        }

        // "Warm-up, set 1" rather than the picker's "Set type, …": nothing about a written row's
        // type can be changed from the row, so announcing it as a control would be a lie.
        composeTestRule.onNodeWithContentDescription("Warm-up, set 1").assertIsDisplayed()
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
        showPrevious: Boolean = true,
        isCompleted: Boolean = false,
        error: String? = null,
        enabled: Boolean = true,
        canComplete: Boolean = true,
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
                    onSetTypeChange = types::add,
                    rpe = rpe,
                    onRpeChange = ratings::add,
                    previous = previous,
                    showPrevious = showPrevious,
                    isCompleted = isCompleted,
                    error = error,
                    enabled = enabled,
                    canComplete = canComplete,
                )
            }
        }
    }

    private companion object {

        /** Layout is computed in floating point; a fraction of a pixel is not a misalignment. */
        const val HALF_A_PIXEL = 0.5f
    }
}
