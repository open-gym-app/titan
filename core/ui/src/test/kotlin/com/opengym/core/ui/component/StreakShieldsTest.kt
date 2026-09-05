package com.opengym.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The allowance row.
 *
 * Its marks are `Icon`s, so what matters here is almost entirely what a screen reader is left
 * with: three shields announced one by one is the failure this component exists to prevent, and
 * it is invisible to anybody reviewing the screenshot.
 */
@RunWith(RobolectricTestRunner::class)
class StreakShieldsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the row is announced as one phrase rather than as its marks`() {
        setContent {
            StreakShields(
                remaining = 2,
                allowance = 3,
                contentDescription = "2 of 3 streak protections left this month",
            )
        }

        composeTestRule.onNodeWithContentDescription("2 of 3 streak protections left this month")
            .assertIsDisplayed()
        // One node, not one per mark: the icons' own semantics are replaced, not added to.
        composeTestRule.onAllNodes(spoken).assertCountEquals(1)
    }

    @Test
    fun `a row whose caller speaks for it says nothing at all`() {
        setContent { StreakShields(remaining = 1, allowance = 3, contentDescription = null) }

        // Silent by request — the card drawing this puts the same sentence in visible text
        // beside it, and a reader must not hear it twice.
        composeTestRule.onAllNodes(spoken).assertCountEquals(0)
    }

    @Test
    fun `a stale count draws the allowance rather than overshooting it`() {
        // Nothing crashes and nothing extra is drawn: the row is always `allowance` marks wide,
        // whatever a caller hands it.
        setContent { StreakShields(remaining = 9, allowance = 3, contentDescription = "full") }

        composeTestRule.onNodeWithContentDescription("full").assertIsDisplayed()
    }

    @Test
    fun `negative - an allowance of nothing is a caller's mistake, not an empty row`() {
        assertThrows(IllegalArgumentException::class.java) {
            setContent { StreakShields(remaining = 0, allowance = 0, contentDescription = "none") }
        }
    }

    /** Any node that a screen reader would announce something for. */
    private val spoken = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent { OpenGymTheme { content() } }
    }
}
