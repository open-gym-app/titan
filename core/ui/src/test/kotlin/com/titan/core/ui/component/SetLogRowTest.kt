package com.titan.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.ui.theme.TitanTheme
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
    private var fills = 0

    @Test
    fun `renders the values it is given`() {
        setRow(weight = "80", reps = "5", rpe = 85, previous = PreviousSet(weight = 75_000_000, reps = 6))

        composeTestRule.onNodeWithText("80").assertIsDisplayed()
        composeTestRule.onNodeWithText("5").assertIsDisplayed()
        composeTestRule.onNodeWithText("8.5").assertIsDisplayed()
    }

    // ─── The target ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a target sits under the row as one button, and in the fields as nothing at all`() {
        setRow(weight = "", reps = "", target = TARGET)

        // The line: one node, the whole sentence, and a tap that asks rather than logs.
        composeTestRule
            .onNodeWithContentDescription(
                "Target 82.5 kg × 3 · +2.5 kg. Hit the top of the range last time — add 2.5 kg",
            )
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, fills)
        assertTrue(completions.isEmpty())

        // The placeholders: drawn in the two fields and absent from the tree, so the fields
        // still read as empty and the target is heard once, from the line.
        composeTestRule.onNodeWithContentDescription("Weight, set 3").assert(hasText(""))
        composeTestRule.onNodeWithContentDescription("Reps, set 3").assert(hasText(""))
        composeTestRule.onAllNodes(hasText("82.5")).assertCountEquals(0)
    }

    @Test
    fun `negative - typing over a target is typing, and the fields say what was typed`() {
        setRow(weight = "85", reps = "", target = TARGET)

        composeTestRule.onNodeWithContentDescription("Weight, set 3").assert(hasText("85"))
        composeTestRule.onNodeWithContentDescription("Reps, set 3").assert(hasText(""))
    }

    @Test
    fun `negative - a row with no target draws no line`() {
        setRow(weight = "", reps = "")

        composeTestRule.onAllNodes(hasContentDescription("Target", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `the target line keeps its place when the previous column goes`() {
        setRow(weight = "", reps = "", showPrevious = false, target = TARGET)

        composeTestRule.onNodeWithContentDescription("Target", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the previous column is announced as a sentence rather than as four characters`() {
        setRow(weight = "80", reps = "5", previous = PreviousSet(weight = 75_000_000, reps = 6))

        // Spelled out, with the unit, where the cell itself draws `75×6`: the column is 52dp
        // between two fields and a sentence is not, which is the whole reason the row takes the
        // quantities rather than the text of them.
        composeTestRule.onNodeWithContentDescription("Previously 75 kg × 6").assertIsDisplayed()
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
    fun `the tick announces its state in words, and reports the state it is being moved to`() {
        setRow(weight = "80", reps = "5")

        // `stateDescription` is what TalkBack says first — "Not completed, Set 3, checkbox" —
        // and it is asserted as its own property rather than folded into the label, which is
        // the whole point of it being one: the label does not change with the state.
        composeTestRule.onNodeWithContentDescription("Set 3")
            .assertIsOff()
            .assert(hasStateDescription("Not completed"))
            .performClick()

        assertEquals(listOf(true), completions)
    }

    @Test
    fun `a completed row offers to reopen the set rather than to complete it`() {
        setRow(weight = "80", reps = "5", isCompleted = true)

        composeTestRule.onNodeWithContentDescription("Set 3")
            .assertIsOn()
            .assert(hasStateDescription("Completed"))
            .performClick()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun `a set with no history keeps the column, so the rows of one table line up`() {
        composeTestRule.setContent {
            TitanTheme {
                Column {
                    SetLogRow(
                        setNumber = 1,
                        weight = "80",
                        reps = "5",
                        onWeightChange = {},
                        onRepsChange = {},
                        onCompletedChange = {},
                        previous = PreviousSet(weight = 75_000_000, reps = 6),
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
            TitanTheme {
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

        composeTestRule.onNodeWithContentDescription("Set 3").assertIsNotEnabled()
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
    fun `a menu the row went read-only under does not spring back open`() {
        var enabled by mutableStateOf(true)
        composeTestRule.setContent {
            TitanTheme {
                SetLogRow(
                    setNumber = 3,
                    weight = "80",
                    reps = "5",
                    onWeightChange = weightEdits::add,
                    onRepsChange = repEdits::add,
                    onCompletedChange = completions::add,
                    onSetTypeChange = types::add,
                    rpe = 75,
                    onRpeChange = ratings::add,
                    enabled = enabled,
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 3, 7.5")
            .performClick()
        composeTestRule.onNodeWithText("Not rated").assertIsDisplayed()

        // The workout is paused under the open menu, and then picked back up. Withholding the
        // popup without closing it leaves the menu up over a row nobody has touched since.
        enabled = false
        composeTestRule.onNodeWithText("Not rated").assertDoesNotExist()
        enabled = true

        composeTestRule.onNodeWithText("Not rated").assertDoesNotExist()
        assertEquals(emptyList<Int?>(), ratings)
    }

    @Test
    fun `a row that does not hold a set yet keeps its fields and loses its tick`() {
        // The narrower half of `enabled`: nothing about the row is read-only, it simply is not
        // a set until the dimension its exercise is logged in has been filled in — and a tick
        // that was live for it would be pressed for a set the write path refuses.
        setRow(weight = "80", reps = "", canComplete = false)

        composeTestRule.onNodeWithContentDescription("Set 3").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Weight, set 3").performTextReplacement("82.5")

        assertEquals(listOf("82.5"), weightEdits)
        assertEquals(emptyList<Boolean>(), completions)
    }

    @Test
    fun `a logged set is formatted from the domain values, not typed`() {
        composeTestRule.setContent {
            TitanTheme {
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
            TitanTheme {
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
            TitanTheme {
                LoggedSetRow(setNumber = 1, set = ActiveSet(reps = 12, isCompleted = true, completedAt = 1))
            }
        }

        composeTestRule.onNodeWithText("12 reps").assertIsDisplayed()
    }

    @Test
    fun `the tick on a logged set takes it back out of the count`() {
        val completions = mutableListOf<Boolean>()
        composeTestRule.setContent {
            TitanTheme {
                LoggedSetRow(
                    setNumber = 2,
                    set = loggedSet(),
                    onCompletedChange = completions::add,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Set 2")
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun `an un-ticked logged set offers the tick back, and stays in the table`() {
        val completions = mutableListOf<Boolean>()
        composeTestRule.setContent {
            TitanTheme {
                LoggedSetRow(
                    setNumber = 2,
                    set = loggedSet().copy(isCompleted = false, completedAt = null),
                    onCompletedChange = completions::add,
                )
            }
        }

        // The values are still there — this is not a deletion — and the tick is the way back.
        composeTestRule.onNodeWithText("70 kg × 8").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Set 2")
            .assertIsOff()
            .performClick()

        assertEquals(listOf(true), completions)
    }

    @Test
    fun `negative - a read-only table draws the tick as a mark rather than a control`() {
        composeTestRule.setContent {
            TitanTheme {
                LoggedSetRow(setNumber = 2, set = loggedSet(), onCompletedChange = null)
            }
        }

        composeTestRule.onNodeWithContentDescription("Set 2").assertDoesNotExist()
    }

    // ─── Leaving a row, which is what writes a logged one ───────────────────────────────

    @Test
    fun `leaving the row reports it`() {
        var commits = 0
        composeTestRule.setContent {
            TitanTheme {
                Column {
                    SetLogRow(
                        setNumber = 1,
                        weight = "70",
                        reps = "8",
                        onWeightChange = {},
                        onRepsChange = {},
                        onCompletedChange = {},
                        onCommit = { commits++ },
                    )
                    // Somewhere else to put the focus, which is what leaving the row is.
                    BasicTextField(value = "", onValueChange = {}, modifier = Modifier.testTag(ELSEWHERE))
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Weight, set 1").requestFocus()
        composeTestRule.onNodeWithTag(ELSEWHERE).requestFocus()

        assertEquals(1, commits)
    }

    @Test
    fun `negative - moving between the two fields of one row is not leaving it`() {
        var commits = 0
        composeTestRule.setContent {
            TitanTheme {
                SetLogRow(
                    setNumber = 1,
                    weight = "70",
                    reps = "8",
                    onWeightChange = {},
                    onRepsChange = {},
                    onCompletedChange = {},
                    onCommit = { commits++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Weight, set 1").requestFocus()
        composeTestRule.onNodeWithContentDescription("Reps, set 1").requestFocus()

        // What has been left is a field, not the row. A commit per field would write the same
        // correction twice on the way across it.
        assertEquals(0, commits)
    }

    @Test
    fun `negative - a row nobody has focused reports nothing`() {
        var commits = 0
        composeTestRule.setContent {
            TitanTheme {
                SetLogRow(
                    setNumber = 1,
                    weight = "70",
                    reps = "8",
                    onWeightChange = {},
                    onRepsChange = {},
                    onCompletedChange = {},
                    onCommit = { commits++ },
                )
            }
        }

        // `onFocusChanged` fires once on composition with nothing focused. Read as "not focused,
        // therefore left", that writes every row of the table the first time it is drawn.
        assertEquals(0, commits)
    }

    private fun loggedSet() =
        ActiveSet(
            reps = 8,
            weight = 70_000_000,
            rpe = 75,
            isCompleted = true,
            completedAt = 1,
        )

    private fun setRow(
        weight: String,
        reps: String,
        setType: SetType = SetType.NORMAL,
        rpe: Int? = null,
        previous: PreviousSet? = null,
        showPrevious: Boolean = true,
        isCompleted: Boolean = false,
        error: String? = null,
        enabled: Boolean = true,
        canComplete: Boolean = true,
        target: LoadSuggestion? = null,
    ) {
        composeTestRule.setContent {
            TitanTheme {
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
                    target = target,
                    onFillTarget = { fills++ },
                )
            }
        }
    }

    private companion object {

        /** What the engine makes of 80 × 6 under the strength range: the step goes on. */
        val TARGET = LoadSuggestion(
            weight = 82_500_000,
            reps = 3,
            reason = SuggestionReason.INCREASE,
            previousWeight = 80_000_000,
            basis = PastPerformance(
                listOf(
                    WorkoutSet(
                        sessionExerciseId = 1,
                        setNumber = 1,
                        reps = 6,
                        weight = 80_000_000,
                        isCompleted = true,
                        completedAt = 0,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                ),
            ),
        )

        /** Layout is computed in floating point; a fraction of a pixel is not a misalignment. */
        const val HALF_A_PIXEL = 0.5f

        /** A field outside the row, so focus has somewhere to go that counts as leaving. */
        const val ELSEWHERE = "elsewhere"
    }
}
