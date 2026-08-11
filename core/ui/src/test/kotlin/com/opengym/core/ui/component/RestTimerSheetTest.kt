package com.opengym.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun `the header indicator announces the remainder rather than reading the digits out`() {
        composeTestRule.setContent {
            OpenGymTheme {
                RestTimerIndicator(
                    state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("1:23 left").assertIsDisplayed()
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
