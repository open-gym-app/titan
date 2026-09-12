package com.opengym.core.ui.chart

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a chart tells a screen reader.
 *
 * The acceptance criterion three separate M5 issues carry — *"values reachable via TalkBack,
 * not image-only"* — is one description and one `clearAndSetSemantics`, so this is the file
 * that holds it. A drawn canvas has nothing else a unit test can assert on; the pixels are the
 * screenshot references' job.
 *
 * The "not announced separately" half is asserted as **the chart having no semantic children**,
 * which is the thing that actually goes wrong: a canvas that merges rather than clears reads
 * out twelve marks before the reader reaches the figure the card is about, exactly as
 * `TrainingCalendar` avoids for its day cells.
 */
@RunWith(RobolectricTestRunner::class)
class ChartSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a line chart announces its range, its latest value and its extremes as one node`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LineSeriesChart(entries = previewRising(), label = "Cumulative volume")
            }
        }

        composeTestRule
            .onNodeWithContentDescription(
                "Cumulative volume, Feb to Apr. Latest 108,400 kg. " +
                    "Highest 108,400 kg in Apr, lowest 40,200 kg in Feb.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `the marks are not announced one by one`() {
        composeTestRule.setContent {
            OpenGymTheme {
                BarSeriesChart(entries = previewPeaked(), label = "Volume per session")
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Volume per session", substring = true)
            .onChildren()
            .assertCountEquals(0)
    }

    @Test
    fun `a flat series says so rather than reporting a highest and a lowest that are equal`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LineSeriesChart(entries = previewFlat(), label = "Body weight")
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Body weight, Mar to Apr. 80.0 kg throughout, unchanged.")
            .assertIsDisplayed()
    }

    @Test
    fun `a single-point series draws the placeholder rather than a chart of one mark`() {
        composeTestRule.setContent {
            OpenGymTheme {
                BarSeriesChart(entries = previewSinglePoint(), label = "Volume per session")
            }
        }

        // One node, and it is the placeholder's own sentence — the drawn "Not enough to chart
        // yet" is cleared along with everything else, so a reader is told once rather than twice.
        composeTestRule
            .onNodeWithContentDescription("Volume per session. Not enough to chart yet.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Not enough to chart yet").assertDoesNotExist()
    }

    @Test
    fun `an empty series draws the placeholder rather than a zeroed chart`() {
        composeTestRule.setContent {
            OpenGymTheme {
                LineSeriesChart(entries = emptyList(), label = "Cumulative volume")
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Cumulative volume. Not enough to chart yet.")
            .assertIsDisplayed()
    }

    @Test
    fun `a series of nothing but zeroes still charts, because a rest week is a real week`() {
        composeTestRule.setContent {
            OpenGymTheme {
                BarSeriesChart(entries = previewZeroed(), label = "Volume per session")
            }
        }

        // Flat, not absent: the weeks happened and nothing was lifted in them.
        composeTestRule
            .onNodeWithContentDescription("Volume per session, Mar to Apr. 0 kg throughout, unchanged.")
            .assertIsDisplayed()
    }

    @Test
    fun `the axis is not read out beside the chart that already names its range`() {
        composeTestRule.setContent {
            OpenGymTheme {
                ChartAxisLabels(labels = chartAxisLabels(previewRising()))
            }
        }

        // Drawn, but silent: `FEB`, `MAR`, `APR` three swipes apart, with no chart between
        // them, is three fragments a reader cannot attach to anything.
        composeTestRule.onNodeWithContentDescription("Feb").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Mar").assertDoesNotExist()
    }

    @Test
    fun `the ring announces its label, its fraction and how far round it has gone`() {
        composeTestRule.setContent {
            OpenGymTheme {
                ProgressRing(progress = 0.713f, label = "Level 14", valueLabel = "2,140 of 3,000 XP") {}
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Level 14, 2,140 of 3,000 XP, 71% complete")
            .assertIsDisplayed()
    }

    @Test
    fun `a ring handed more than a whole is drawn full rather than wrapped past the top`() {
        composeTestRule.setContent {
            OpenGymTheme {
                ProgressRing(progress = 1.4f, label = "Level 14", valueLabel = "4,200 of 3,000 XP") {}
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Level 14, 4,200 of 3,000 XP, 100% complete")
            .assertIsDisplayed()
    }
}
