package com.titan.core.ui.component

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The join between two exercises, as a screen reader meets it.
 *
 * The control is one merged node over a pill and two rules, and `clearAndSetSemantics` is
 * what merges it — which also throws away the pill's own click. What these hold is that the
 * node still *does* something when a screen reader activates it, not merely when a finger
 * lands on the pill: the screen tests click by gesture, which passes over a node with no
 * action at all.
 */
@RunWith(RobolectricTestRunner::class)
class SupersetLinkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `an offer says the pill's own word before what a press does`() {
        setContent(isLinked = false)

        // The drawn word leads: Accessibility Scanner holds a node's label to the text it can
        // see on it, and a Voice Access user says "Superset" because that is what is written.
        composeTestRule
            .onNodeWithContentDescription("Superset. Perform Bench press and Cable fly back to back")
            .assertIsDisplayed()
            .assertHasClickAction()
        composeTestRule.onNodeWithText("SUPERSET").assertDoesNotExist()
    }

    @Test
    fun `a join names its group the way the pill does`() {
        setContent(isLinked = true, groupLabel = "A")

        composeTestRule
            .onNodeWithContentDescription("Superset A. Stop performing Bench press and Cable fly back to back")
            .assertIsDisplayed()
    }

    @Test
    fun `activating the node from a screen reader presses the pill`() {
        var presses = 0
        setContent(isLinked = false, onClick = { presses++ })

        // The semantics action, not a tap: this is the path TalkBack's double-tap takes, and
        // the one a merged node loses unless the join hands it back.
        composeTestRule
            .onNodeWithContentDescription("Superset. Perform Bench press and Cable fly back to back")
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, presses)
    }

    @Test
    fun `negative - a disabled join is announced as such and takes no press`() {
        var presses = 0
        setContent(isLinked = false, enabled = false, onClick = { presses++ })

        val node = composeTestRule
            .onNodeWithContentDescription("Superset. Perform Bench press and Cable fly back to back")
            .assertIsNotEnabled()
        node.performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(0, presses)
    }

    private fun setContent(
        isLinked: Boolean,
        groupLabel: String? = null,
        enabled: Boolean = true,
        onClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            TitanTheme {
                SupersetLink(
                    isLinked = isLinked,
                    exerciseName = "Bench press",
                    nextExerciseName = "Cable fly",
                    onClick = onClick,
                    groupLabel = groupLabel,
                    enabled = enabled,
                )
            }
        }
    }
}
