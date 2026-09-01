package com.opengym.core.ui.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a chart says when a mark is touched.
 *
 * The complaint these cards came back with was that a shape on a card says which way a lift is
 * going and nothing says what it is worth. Two things answer it — the figures down the left,
 * and this — and only one of them can be asserted here: the value axis is hidden from screen
 * readers on purpose, like the month labels under it, so its figures are the screenshot
 * references' job and its arithmetic is `ChartSeriesTest`'s. What a tooltip *says* is ordinary
 * text on the screen, so it is this file's.
 *
 * The taps go through the plot's own node. A canvas that has cleared its semantics is still a
 * rectangle a pointer lands on, and the position is what decides which mark comes back — which
 * is the half of the behaviour a click on a button could not cover.
 */
@RunWith(RobolectricTestRunner::class)
class ChartTooltipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `touching a bar says which session it was and what it was worth`() {
        composeTestRule.setContent {
            OpenGymTheme { volumeCard(previewPeaked()) }
        }

        composeTestRule.onNodeWithText(MIDDLE_BAR).assertDoesNotExist()

        plot().performTouchInput { click() }

        // The centre of eight bars is the fifth of them, and the tooltip carries the period it
        // covers beside the figure — see `ChartEntry.caption`.
        composeTestRule.onNodeWithText(MIDDLE_BAR).assertIsDisplayed()
        composeTestRule.onNodeWithText(MIDDLE_BAR_PERIOD).assertIsDisplayed()
    }

    @Test
    fun `touching the same bar again puts the tooltip away`() {
        composeTestRule.setContent {
            OpenGymTheme { volumeCard(previewPeaked()) }
        }

        plot().performTouchInput { click() }
        composeTestRule.onNodeWithText(MIDDLE_BAR).assertIsDisplayed()

        plot().performTouchInput { click() }

        // There is nowhere else on a card to tap a tooltip away, so the mark it is open on has
        // to be able to close it.
        composeTestRule.onNodeWithText(MIDDLE_BAR).assertDoesNotExist()
    }

    @Test
    fun `a new series closes the tooltip rather than leaving it on whatever is now mark five`() {
        var entries by mutableStateOf(previewPeaked())
        composeTestRule.setContent {
            OpenGymTheme { volumeCard(entries) }
        }

        plot().performTouchInput { click() }
        composeTestRule.onNodeWithText(MIDDLE_BAR).assertIsDisplayed()

        // A timeframe switched under an open tooltip. The index would still be in range and
        // would still resolve — to a session the user never touched.
        entries = previewRising()

        composeTestRule.onNodeWithText(MIDDLE_BAR).assertDoesNotExist()
    }

    /**
     * The plot, found by the sentence it announces itself with.
     *
     * The card's eyebrow carries the label on its own — `chartDescription` opens with it and
     * then says the range — so the comma is what tells the two apart. See `ChartSemanticsTest`.
     */
    private fun plot() = composeTestRule.onNodeWithContentDescription("$LABEL, ", substring = true)

    /** The volume card as the exercise screen builds it: bars, from zero, with a value axis. */
    @Composable
    private fun volumeCard(entries: List<ChartEntry>) {
        ChartCard(
            label = LABEL,
            entries = entries,
            scale = ChartScale.BAR,
            formatValue = ::previewFormat,
        )
    }

    private companion object {
        const val LABEL = "Volume per session"

        /** The fifth of `previewPeaked`'s eight bars — the one under the centre of the card. */
        const val MIDDLE_BAR = "11,300 kg"
        const val MIDDLE_BAR_PERIOD = "MAR"
    }
}
