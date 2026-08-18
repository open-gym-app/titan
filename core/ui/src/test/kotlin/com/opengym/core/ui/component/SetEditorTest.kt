package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
 * A phone-sized viewport rather than Robolectric's 320 × 470 default: the editor is a row plus
 * a nine-cell rating scale plus a row of chips, and on a screen no phone has shipped with this
 * decade the actions under it are off the bottom before the test has touched anything.
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
    private var deletes = 0
    private var cancels = 0

    @Test
    fun `the set's stored values are what is in the fields`() {
        setEditor(weight = "70", reps = "8", rpe = 75)

        // Asserted through the field's own label rather than by its contents: the rating scale
        // under the row draws the digits 6 to 10, so "the node whose text is 8" is two nodes.
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
    fun `the rating is picked off the scale rather than typed`() {
        setEditor(weight = "70", reps = "8", rpe = 75)

        composeTestRule.onNodeWithContentDescription("Rate of perceived exertion 9").performClick()

        assertEquals(listOf(90), ratings)
    }

    @Test
    fun `tapping the rating that is already set clears it`() {
        setEditor(weight = "70", reps = "8", rpe = 75)

        composeTestRule.onNodeWithContentDescription("Rate of perceived exertion 7.5").performClick()

        assertEquals(listOf<Int?>(null), ratings)
    }

    @Test
    fun `the set type is changed from the chips under the row`() {
        setEditor(weight = "70", reps = "8")

        // Uppercased by the chip, not by the resource: `OpenGymFilterChip` does the typographic
        // treatment so a translator never has to reproduce one.
        composeTestRule.onNodeWithText("WARM-UP").performClick()

        assertEquals(listOf(SetType.WARMUP), types)
    }

    @Test
    fun `the tick writes the correction`() {
        setEditor(weight = "70", reps = "8")

        composeTestRule.onNodeWithContentDescription("Save set 2").performClick()

        assertEquals(1, saves)
        assertEquals("saving is not deleting", 0, deletes)
    }

    @Test
    fun `delete and cancel are the other two ways out`() {
        setEditor(weight = "70", reps = "8")

        composeTestRule.onNodeWithText("Delete set").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, deletes)
        assertEquals(1, cancels)
        assertEquals("neither of them writes the values", 0, saves)
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
        setEditor(weight = "70", reps = "8", enabled = false)

        composeTestRule.onNodeWithContentDescription("Save set 2").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Delete set").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Rate of perceived exertion 9").performClick()

        assertEquals(emptyList<Int?>(), ratings)
    }

    // ─── Reopening a logged row ─────────────────────────────────────────────────────────

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
                    onDelete = { deletes++ },
                    onCancel = { cancels++ },
                    error = error,
                    enabled = enabled,
                    canSave = canSave,
                )
            }
        }
    }
}
