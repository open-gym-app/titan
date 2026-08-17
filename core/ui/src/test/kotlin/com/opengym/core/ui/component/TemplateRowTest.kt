package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The saved-template row, which three screens draw and none of them owns.
 *
 * The assertions worth having here are about what the row *says*: the figures on it are
 * derived, and a screen that rendered them itself would be free to count a superset as one
 * exercise while another counted two.
 */
@RunWith(RobolectricTestRunner::class)
class TemplateRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a row shows the name, the count, the estimate and the category`() {
        setContent(summary("Push Day · Upper", 8, 55.minutes, TemplateCategory.STRENGTH))

        composeTestRule.onNodeWithContentDescription(
            "Push Day · Upper, 8 exercises, about 55 minutes, Strength",
        ).assertIsDisplayed()
    }

    @Test
    fun `an uncategorised template draws no chip`() {
        // Null is not GENERAL: it means the user was never asked, and a chip reading
        // "General" would put an answer in their mouth. See TemplateCategory.
        setContent(summary("Push Day", 8, 55.minutes, category = null))

        composeTestRule.onNodeWithText("GENERAL").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Push Day, 8 exercises, about 55 minutes")
            .assertIsDisplayed()
    }

    @Test
    fun `a template with nothing in it drops the estimate rather than reading zero`() {
        // What a template whose exercises were deleted underneath it looks like. "0 ex · ~0m"
        // states the same absence twice, and the second reads as a measurement.
        setContent(summary("Emptied out", 0, Duration.ZERO))

        composeTestRule.onNodeWithContentDescription("Emptied out, 0 exercises").assertIsDisplayed()
    }

    @Test
    fun `a row reports the tap that starts from it`() {
        var taps = 0
        composeTestRule.setContent {
            OpenGymTheme {
                TemplateRow(summary = summary("Push Day", 8, 55.minutes), onClick = { taps++ })
            }
        }

        composeTestRule.onNodeWithContentDescription("Push Day, 8 exercises, about 55 minutes")
            .performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `a row shown rather than offered takes no click`() {
        setContent(summary("Push Day", 8, 55.minutes))

        composeTestRule.onNodeWithContentDescription("Push Day, 8 exercises, about 55 minutes")
            .assertHasNoClickAction()
    }

    @Test
    fun `a clickable row carries the click role`() {
        composeTestRule.setContent {
            OpenGymTheme {
                TemplateRow(summary = summary("Push Day", 8, 55.minutes), onClick = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Push Day, 8 exercises, about 55 minutes")
            .assertHasClickAction()
    }

    @Test
    fun `the row speaks as one node, not as four`() {
        // Cleared semantics: a reader walking a list of six templates should hear six rows,
        // not twenty-four fragments.
        setContent(summary("Push Day", 8, 55.minutes, TemplateCategory.STRENGTH))

        composeTestRule.onNodeWithText("Push Day").assertDoesNotExist()
        composeTestRule.onNodeWithText("8 ex · ~55m").assertDoesNotExist()
    }

    @Test
    fun `a trailing slot is drawn after the category`() {
        composeTestRule.setContent {
            OpenGymTheme {
                Column {
                    TemplateRow(
                        summary = summary("Push Day", 8, 55.minutes, TemplateCategory.STRENGTH),
                        trailing = { Text(text = "›") },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("›").assertIsDisplayed()
    }

    private fun setContent(summary: TemplateSummary) {
        composeTestRule.setContent { OpenGymTheme { TemplateRow(summary = summary) } }
    }

    private fun summary(
        name: String,
        exerciseCount: Int,
        duration: Duration,
        category: TemplateCategory? = null,
    ) = TemplateSummary(
        template = WorkoutTemplate(id = 1L, name = name, category = category),
        exerciseCount = exerciseCount,
        estimatedDuration = duration,
    )
}
