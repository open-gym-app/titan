package com.opengym.core.ui.component

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.opengym.core.domain.timer.RestTimerState
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The rest timer sheet across the four states of [RestTimerState].
 *
 * The states are the point: a countdown that renders `Running` correctly and `Paused` as a
 * running one is the failure this component is most likely to have, and it is invisible in a
 * screenshot of the happy path.
 */
@RunWith(RobolectricTestRunner::class)
class RestTimerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val adjustments = mutableListOf<Duration>()
    private var skips = 0
    private var pauseToggles = 0

    @Test
    fun `a running rest shows what is left and what it is out of`() {
        setSheet(RestTimerState.Running(remaining = 83.seconds, total = 120.seconds))

        composeTestRule.onNodeWithText("1:23").assertIsDisplayed()
        composeTestRule.onNodeWithText("of 2:00 · Next 80 kg × 5").assertIsDisplayed()
        composeTestRule.onNodeWithText("REST").assertIsDisplayed()
    }

    @Test
    fun `a paused rest says so and keeps the remainder it was frozen at`() {
        setSheet(RestTimerState.Paused(remaining = 83.seconds, total = 120.seconds))

        composeTestRule.onNodeWithText("REST PAUSED").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:23").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Resume the rest").assertIsDisplayed()
    }

    @Test
    fun `a finished rest reads zero and offers the next set instead of a skip`() {
        setSheet(RestTimerState.Finished)

        composeTestRule.onNodeWithText("REST COMPLETE").assertIsDisplayed()
        composeTestRule.onNodeWithText("0:00").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next set").assertIsDisplayed()
    }

    @Test
    fun `an idle timer draws a dormant sheet rather than crashing`() {
        setSheet(RestTimerState.Idle)

        composeTestRule.onNodeWithText("NO REST RUNNING").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Extend the rest by 15 seconds").assertIsNotEnabled()
    }

    @Test
    fun `the adjust buttons report a signed duration`() {
        setSheet(RestTimerState.Running(remaining = 83.seconds, total = 120.seconds))

        composeTestRule.onNodeWithContentDescription("Extend the rest by 15 seconds").performClick()
        composeTestRule.onNodeWithContentDescription("Shorten the rest by 15 seconds").performClick()

        assertEquals(listOf(15.seconds, (-15).seconds), adjustments)
    }

    @Test
    fun `the adjust buttons are live while a rest is paused`() {
        setSheet(RestTimerState.Paused(remaining = 83.seconds, total = 120.seconds))

        composeTestRule.onNodeWithContentDescription("Extend the rest by 15 seconds").assertIsEnabled()
    }

    @Test
    fun `negative - a finished rest cannot be adjusted`() {
        setSheet(RestTimerState.Finished)

        composeTestRule.onNodeWithContentDescription("Shorten the rest by 15 seconds").assertIsNotEnabled()
        assertEquals(emptyList<Duration>(), adjustments)
    }

    @Test
    fun `the primary action skips the rest`() {
        setSheet(RestTimerState.Running(remaining = 83.seconds, total = 120.seconds))

        composeTestRule.onNodeWithText("Skip rest").performClick()

        assertEquals(1, skips)
    }

    @Test
    fun `pausing is reported once per press`() {
        setSheet(RestTimerState.Running(remaining = 83.seconds, total = 120.seconds))

        composeTestRule.onNodeWithContentDescription("Pause the rest").performClick()

        assertEquals(1, pauseToggles)
    }

    @Test
    fun `the pause control is absent when the screen does not offer one`() {
        composeTestRule.setContent {
            OpenGymTheme {
                RestTimerSheet(
                    state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
                    onAdjust = adjustments::add,
                    onSkip = { skips++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Pause the rest").assertDoesNotExist()
    }

    // ─── The live region ────────────────────────────────────────────────────────────────

    @Test
    fun `the status line is a polite live region that rounds the remainder up to the quarter-minute`() {
        setSheet(RestTimerState.Running(remaining = 83.seconds, total = 120.seconds))

        // "1:30", not "1:23": the region speaks every time its text changes, so the text changes
        // four times a minute rather than sixty. The exact figure is still on the digits below.
        composeTestRule
            .onNodeWithContentDescription("Resting, 1:30 left")
            .assertIsDisplayed()
            .assert(expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeTestRule.onNodeWithContentDescription("1:23 left").assertIsDisplayed()
    }

    @Test
    fun `a remainder on the quarter-minute is announced as it is, and the last one is fifteen seconds`() {
        setSheet(RestTimerState.Running(remaining = 60.seconds, total = 120.seconds))
        composeTestRule.onNodeWithContentDescription("Resting, 1:00 left").assertIsDisplayed()
    }

    @Test
    fun `the final seconds announce as a quarter-minute rather than as nothing left`() {
        // Rounded up: "0:00 left" with three seconds to go would be a lie the alert then
        // contradicts. The next thing said after this is "Rest complete".
        setSheet(RestTimerState.Running(remaining = 3.seconds, total = 120.seconds))
        composeTestRule.onNodeWithContentDescription("Resting, 0:15 left").assertIsDisplayed()
    }

    @Test
    fun `a paused rest is announced exactly, since nothing about it is going to change`() {
        setSheet(RestTimerState.Paused(remaining = 83.seconds, total = 120.seconds))
        composeTestRule
            .onNodeWithContentDescription("Rest paused, 1:23 left")
            .assert(expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `a finished rest announces that it is over`() {
        setSheet(RestTimerState.Finished)
        composeTestRule
            .onNodeWithContentDescription("Rest complete")
            .assert(expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `the header pill is the live region only when told it is the one readout on screen`() {
        composeTestRule.setContent {
            OpenGymTheme {
                RestTimerIndicator(
                    state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
                    announces = true,
                )
            }
        }

        // Announcing, the pill says the quarter-minute — the description is what the region
        // speaks each time it changes — rather than the exact second it says when it is not.
        composeTestRule
            .onNodeWithContentDescription("Resting, 1:30 left")
            .assert(expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `the header indicator announces the remainder rather than reading the digits out`() {
        composeTestRule.setContent {
            OpenGymTheme {
                RestTimerIndicator(
                    state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
                )
            }
        }

        // And not live: with the sheet up, the sheet's own status line is the region, and two
        // windows announcing the same quarter-minute would say it twice.
        composeTestRule
            .onNodeWithContentDescription("1:23 left")
            .assertIsDisplayed()
            .assert(SemanticsMatcher("has no live region") { SemanticsProperties.LiveRegion !in it.config })
    }

    @Test
    fun `the indicator says what pressing it does, and stays quiet where it does nothing`() {
        var reopens = 0
        composeTestRule.setContent {
            OpenGymTheme {
                RestTimerIndicator(
                    state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
                    onClick = { reopens++ },
                )
            }
        }

        // One stop in the traversal order, carrying the time and what a press is for. Four
        // digits announced as "one twenty-three" name nothing at all.
        val pill = composeTestRule.onNode(
            hasContentDescription("1:23 left") and
                hasClickAction() and
                SemanticsMatcher("has a labelled click action") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == "Show the rest timer"
                },
        )

        pill.performClick()
        assertEquals(1, reopens)

        // And through the action itself, which is the one a screen reader invokes. It is
        // asserted separately because it is separately breakable: the pill sets its semantics
        // with `clearAndSetSemantics`, so a label declared without an action leaves a node
        // that a finger can press and TalkBack cannot.
        pill.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, reopens)
    }

    private fun setSheet(state: RestTimerState) {
        composeTestRule.setContent {
            OpenGymTheme {
                RestTimerSheet(
                    state = state,
                    onAdjust = adjustments::add,
                    onSkip = { skips++ },
                    onTogglePause = { pauseToggles++ },
                    nextSet = "80 kg × 5",
                )
            }
        }
    }
}
