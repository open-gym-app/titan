package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
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
import org.robolectric.annotation.Config

/**
 * A logged set reopened: what the editor draws, and what it reports.
 *
 * Driven stateless like [SetLogRowTest], which is the same contract in the same grid — the test
 * supplies the text and asserts on the change reported, because the fields hold nothing.
 *
 * A phone-sized viewport rather than Robolectric's 320 × 470 default, so the menus the two picker
 * cells open have somewhere to be drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SetEditorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val weightEdits = mutableListOf<String>()
    private val repEdits = mutableListOf<String>()
    private val ratings = mutableListOf<Int?>()
    private val types = mutableListOf<SetType>()
    private var saves = 0
    private var cancels = 0

    @Test
    fun `the set's stored values are what is in the fields`() {
        setEditor(weight = "70", reps = "8", rpe = 75)

        // Asserted through each cell's own label rather than by its contents: three cells of this
        // row hold digits, so "the node whose text is 8" is not one node.
        composeTestRule.onNodeWithContentDescription("Weight, set 2").assert(hasText("70"))
        composeTestRule.onNodeWithContentDescription("Reps, set 2").assert(hasText("8"))
        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 2, 7.5")
            .assertIsDisplayed()
    }

    @Test
    fun `reports what was typed over the weight`() {
        setEditor(weight = "70", reps = "8")

        composeTestRule.onNodeWithContentDescription("Weight, set 2").performTextReplacement("72.5")

        assertEquals(listOf("72.5"), weightEdits)
        assertEquals(emptyList<String>(), repEdits)
    }

    @Test
    fun `reports what was typed over the reps`() {
        setEditor(weight = "70", reps = "8")

        composeTestRule.onNodeWithContentDescription("Reps, set 2").performTextReplacement("6")

        assertEquals(listOf("6"), repEdits)
    }

    @Test
    fun `the rating is picked off a menu rather than typed`() {
        setEditor(weight = "70", reps = "8", rpe = 75)

        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 2, 7.5")
            .performClick()
        composeTestRule.onNodeWithContentDescription("Rate of perceived exertion 9").performClick()

        assertEquals(listOf(90), ratings)
    }

    @Test
    fun `the menu's last item is the way back to an unrated set`() {
        setEditor(weight = "70", reps = "8", rpe = 75)

        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 2, 7.5")
            .performClick()
        composeTestRule.onNodeWithText("Not rated").performClick()

        assertEquals(listOf<Int?>(null), ratings)
    }

    @Test
    fun `the set type is picked from the cell that shows it`() {
        setEditor(weight = "70", reps = "8")

        composeTestRule.onNodeWithContentDescription("Set type, set 2, Standard set").performClick()
        composeTestRule.onNodeWithText("Warm-up").performClick()

        assertEquals(listOf(SetType.WARMUP), types)
    }

    @Test
    fun `the tick writes the correction`() {
        setEditor(weight = "70", reps = "8")

        composeTestRule.onNodeWithContentDescription("Save set 2").performClick()

        assertEquals(1, saves)
    }

    @Test
    fun `cancel is the other way out, and writes nothing`() {
        setEditor(weight = "70", reps = "8")

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancels)
        assertEquals("cancelling is not saving", 0, saves)
    }

    @Test
    fun `negative - there is no delete under the fields`() {
        setEditor(weight = "70", reps = "8")

        // Deleting a set is a drag across the row — see `SwipeToDelete`. A word here, one line
        // under the fields and the width of a thumb, is the button that costs somebody a set they
        // had done.
        composeTestRule.onNodeWithText("Delete set").assertDoesNotExist()
    }

    @Test
    fun `a refused correction shows the reason and announces it on the fields`() {
        setEditor(weight = "70", reps = "0", error = "reps must be within 1..10,000, was 0")

        composeTestRule.onNodeWithText("reps must be within 1..10,000, was 0").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Reps, set 2")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    "reps must be within 1..10,000, was 0",
                ),
            )
    }

    @Test
    fun `a correction that is not yet a set keeps its fields and loses its tick`() {
        // The narrower half of `enabled`, as on the entry row: nothing here is read-only, the
        // values simply are not a set the write path would take yet.
        setEditor(weight = "70", reps = "", canSave = false)

        composeTestRule.onNodeWithContentDescription("Save set 2").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Reps, set 2").performTextReplacement("8")

        assertEquals(listOf("8"), repEdits)
        assertEquals(0, saves)
    }

    @Test
    fun `a read-only editor takes nothing, not even a rating`() {
        setEditor(weight = "70", reps = "8", rpe = 75, enabled = false)

        composeTestRule.onNodeWithContentDescription("Save set 2").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
        composeTestRule
            .onNodeWithContentDescription("Rate of perceived exertion, set 2, 7.5")
            .performClick()
        composeTestRule.onNodeWithContentDescription("Set type, set 2, Standard set").performClick()

        composeTestRule.onNodeWithText("Not rated").assertDoesNotExist()
        composeTestRule.onNodeWithText("Warm-up").assertDoesNotExist()
        assertEquals(emptyList<Int?>(), ratings)
        assertEquals(emptyList<SetType>(), types)
    }

    // ─── Reopening a logged row, and un-ticking one ─────────────────────────────────────

    @Test
    fun `a logged row reports the tap that reopens it`() {
        var opened = 0
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(setNumber = 2, set = loggedSet(), onClick = { opened++ })
            }
        }

        composeTestRule.onNodeWithText("70 kg × 8").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a row that can be reopened says so, as one target`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(setNumber = 2, set = loggedSet(), onClick = {})
            }
        }

        // One stop with a click action rather than four cells and a press nobody can reach:
        // the label is what TalkBack reads out as what the double tap will do.
        val label = composeTestRule.onNodeWithText("70 kg × 8")
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .label

        assertEquals("Edit set 2", label)
    }

    @Test
    fun `negative - a table that cannot be edited has no click action on its rows`() {
        composeTestRule.setContent {
            OpenGymTheme {
                Column {
                    LoggedSetRow(setNumber = 2, set = loggedSet(), onClick = null)
                }
            }
        }

        composeTestRule.onNodeWithText("70 kg × 8")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `the tick on a logged row takes the set back out of the count`() {
        val completions = mutableListOf<Boolean>()
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(
                    setNumber = 2,
                    set = loggedSet(),
                    onClick = {},
                    onCompletedChange = completions::add,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Set 2 is done. Un-tick it")
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), completions)
    }

    @Test
    fun `an un-ticked row offers the tick back, and stays in the table`() {
        val completions = mutableListOf<Boolean>()
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(
                    setNumber = 2,
                    set = loggedSet().copy(isCompleted = false, completedAt = null),
                    onClick = {},
                    onCompletedChange = completions::add,
                )
            }
        }

        // The values are still there — this is not a deletion — and the tick is the way back.
        composeTestRule.onNodeWithText("70 kg × 8").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Mark set 2 done")
            .assertIsOff()
            .performClick()

        assertEquals(listOf(true), completions)
    }

    @Test
    fun `the tick is its own target, so reopening the set and un-ticking it are two actions`() {
        var opened = 0
        val completions = mutableListOf<Boolean>()
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(
                    setNumber = 2,
                    set = loggedSet(),
                    onClick = { opened++ },
                    onCompletedChange = completions::add,
                )
            }
        }

        // A row merged whole would swallow the tick's own click: TalkBack would be able to reopen
        // the set and unable to un-tick it, and a thumb aiming at the tick would open the editor.
        composeTestRule.onNodeWithContentDescription("Set 2 is done. Un-tick it").performClick()

        assertEquals(listOf(false), completions)
        assertEquals("un-ticking is not editing", 0, opened)
    }

    @Test
    fun `negative - a read-only table draws the tick as a mark rather than a control`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LoggedSetRow(setNumber = 2, set = loggedSet(), onCompletedChange = null)
            }
        }

        composeTestRule.onNodeWithContentDescription("Set 2 is done. Un-tick it").assertDoesNotExist()
    }

    private fun loggedSet() = ActiveSet(
        reps = 8,
        weight = 70_000_000,
        rpe = 75,
        isCompleted = true,
        completedAt = 1,
    )

    private fun setEditor(
        weight: String,
        reps: String,
        rpe: Int? = null,
        setType: SetType = SetType.NORMAL,
        error: String? = null,
        enabled: Boolean = true,
        canSave: Boolean = true,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                SetEditor(
                    setNumber = 2,
                    weight = weight,
                    reps = reps,
                    onWeightChange = weightEdits::add,
                    onRepsChange = repEdits::add,
                    rpe = rpe,
                    onRpeChange = ratings::add,
                    setType = setType,
                    onSetTypeChange = types::add,
                    onSave = { saves++ },
                    onCancel = { cancels++ },
                    error = error,
                    enabled = enabled,
                    canSave = canSave,
                )
            }
        }
    }
}
