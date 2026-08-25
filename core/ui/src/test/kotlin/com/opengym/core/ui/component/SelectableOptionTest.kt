package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two primitives a question asked *as a screen* is built from: the option card and the
 * rail that says how many questions are left.
 *
 * One class, because they are one pair — the onboarding flow is the shape they exist for, and
 * both are here so a second multi-step flow does not invent its own version of either.
 */
@RunWith(RobolectricTestRunner::class)
class SelectableOptionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val clicks = mutableListOf<String>()

    // ─── The option card ────────────────────────────────────────────────────────────────

    @Test
    fun `an option draws its name and what it means`() {
        setContent()

        composeTestRule.onNodeWithText(STRENGTH).assertIsDisplayed()
        composeTestRule.onNodeWithText(STRENGTH_DETAIL).assertIsDisplayed()
    }

    @Test
    fun `the chosen one is the selected one, and only it`() {
        setContent(selected = HYPERTROPHY)

        composeTestRule.onNodeWithText(HYPERTROPHY).assertIsSelected()
        composeTestRule.onNodeWithText(STRENGTH).assertIsNotSelected()
    }

    @Test
    fun `the whole card is the target, not the radio inside it`() {
        setContent()

        // The card owns the click and the semantics — a radio with a handler of its own would
        // be a second, smaller target announcing the same thing twice. Tapping the *detail*
        // line is what proves the target is the card: it is the corner furthest from the radio.
        composeTestRule.onNodeWithText(STRENGTH_DETAIL).assertHasClickAction().performClick()

        assertEquals(listOf(STRENGTH), clicks)
    }

    // ─── The step rail ──────────────────────────────────────────────────────────────────

    @Test
    fun `the rail says nothing, because the counter beside it already does`() {
        composeTestRule.setContent {
            OpenGymTheme {
                Column {
                    Eyebrow(text = "Step 2 of 4")
                    StepProgress(step = 2, steps = 4)
                }
            }
        }

        composeTestRule.onNodeWithText("STEP 2 OF 4").assertIsDisplayed()

        // The rail's semantics are cleared, so a screen reader finds the label and nothing
        // else. Two nodes saying "step 2 of 4" — one of them a progress bar announcing 50% —
        // is the failure this guards against. The one description left is the eyebrow's own:
        // it hands TalkBack the sentence-case original rather than the shouted pixels.
        composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("Step 2 of 4").assertIsDisplayed()
        composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test
    fun `a step outside the flow is clamped rather than rejected`() {
        composeTestRule.setContent {
            OpenGymTheme {
                Column {
                    StepProgress(step = 9, steps = 4)
                    StepProgress(step = -1, steps = 4)
                }
            }
        }

        // Composing at all *is* the assertion: the fill is a fraction of the track's width,
        // and `fillMaxWidth` rejects a fraction outside 0..1. An off-by-one in a caller should
        // draw a full or an empty rail, not crash the screen it is on.
        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun `a flow with no steps is a programming error, not an empty rail`() {
        val failure = runCatching {
            composeTestRule.setContent { OpenGymTheme { StepProgress(step = 1, steps = 0) } }
        }.exceptionOrNull()

        // Division by the step count is the whole of the geometry, so zero has no drawing to
        // fall back to — and a caller who computed it is wrong about their own flow.
        assertTrue(failure is IllegalArgumentException)
    }

    private fun setContent(selected: String = STRENGTH) {
        composeTestRule.setContent {
            OpenGymTheme {
                Column(modifier = Modifier.selectableGroup()) {
                    SelectableOption(
                        label = STRENGTH,
                        detail = STRENGTH_DETAIL,
                        selected = selected == STRENGTH,
                        onClick = { clicks += STRENGTH },
                    )
                    SelectableOption(
                        label = HYPERTROPHY,
                        detail = HYPERTROPHY_DETAIL,
                        selected = selected == HYPERTROPHY,
                        onClick = { clicks += HYPERTROPHY },
                    )
                }
            }
        }
    }

    private companion object {
        const val STRENGTH = "Strength"
        const val STRENGTH_DETAIL = "Heavy, low reps, long rests"
        const val HYPERTROPHY = "Hypertrophy"
        const val HYPERTROPHY_DETAIL = "Volume and time under tension"
    }
}
