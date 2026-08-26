package com.opengym.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The row every list collapses to while one of its items is held.
 *
 * What it *looks* like is a screenshot's question — the workout's own previews cover both
 * schemes. What is asserted here is the thing a screenshot cannot see: that the whole row is one
 * node to a screen reader, read as its place and its name, so a list being dragged is a list of
 * positions rather than four stops per item that mean nothing apart.
 */
@RunWith(RobolectricTestRunner::class)
class CollapsedReorderRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the row is announced as its place and its name`() {
        setContent(position = 2, name = "Cable fly")

        composeTestRule.onNodeWithContentDescription("3. Cable fly").assertIsDisplayed()
    }

    @Test
    fun `the drawn number is the place being created, counting from one`() {
        setContent(position = 2, name = "Cable fly")

        composeTestRule.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the superset survives the collapse, because a drag is what tears one`() {
        setContent(position = 0, name = "Cable fly", supersetLabel = "A1")

        composeTestRule.onNodeWithText("SS A1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a fact worth keeping is drawn beside the name`() {
        setContent(position = 0, name = "Bench press", meta = "3 sets")

        composeTestRule.onNodeWithText("3 sets", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun setContent(
        position: Int,
        name: String,
        meta: String? = null,
        supersetLabel: String? = null,
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                CollapsedReorderRow(
                    position = position,
                    name = name,
                    meta = meta,
                    supersetLabel = supersetLabel,
                )
            }
        }
    }
}
