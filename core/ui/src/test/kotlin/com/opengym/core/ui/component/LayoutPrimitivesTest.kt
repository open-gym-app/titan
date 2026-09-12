package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two primitives a reading screen is built from: the section heading and the fact grid.
 *
 * One class, because they are one *pair* — a heading over a grid is the shape of every
 * informational block in the app, and both of them exist to stop six screens inventing their
 * own version of it.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutPrimitivesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent { OpenGymTheme { Column { content() } } }
    }

    // ─── The section heading ────────────────────────────────────────────────────────────

    @Test
    fun `a heading shouts its text for the caller`() {
        setContent { SectionHeading(text = "Muscles worked") }

        composeTestRule.onNodeWithText("MUSCLES WORKED").assertIsDisplayed()
    }

    @Test
    fun `a heading is announced as one, so heading navigation can find it`() {
        setContent { SectionHeading(text = "How to") }

        // TalkBack's heading jump is the only way to skip a section of a long reading screen,
        // and it works off this flag rather than off the text looking like a title.
        composeTestRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
    }

    @Test
    fun `a heading draws whatever the caller puts after its rule`() {
        setContent {
            SectionHeading(text = "History", trailing = { StatChip(value = "24", label = "sessions") })
        }

        composeTestRule.onNodeWithContentDescription("24 sessions").assertIsDisplayed()
    }

    // ─── The fact grid ──────────────────────────────────────────────────────────────────

    @Test
    fun `a grid draws every entry it is given`() {
        setContent {
            MetaGrid(
                entries = listOf(
                    MetaEntry(label = "Equipment", value = "Barbell"),
                    MetaEntry(label = "Difficulty", value = "Intermediate"),
                    MetaEntry(label = "Tracked", value = "Weight, reps, RPE"),
                ),
            )
        }

        // Each cell is one node — the label and the value are read together or the pairing is
        // lost — so the assertion is on the phrase rather than on the two halves.
        composeTestRule.onNodeWithContentDescription("Equipment, Barbell").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Difficulty, Intermediate").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tracked, Weight, reps, RPE").assertIsDisplayed()
    }

    @Test
    fun `negative - a grid with nothing in it draws no card`() {
        // The caller builds its entries with `listOfNotNull`, so "every fact was null" is the
        // ordinary state of a user-authored row rather than a bug. An empty bordered card in
        // the middle of a screen reads as a section that failed to load.
        setContent { MetaGrid(entries = emptyList()) }

        composeTestRule.onRoot().onChildren().assertCountEquals(0)
    }

    @Test
    fun `an odd entry out is still drawn`() {
        // Five entries into two columns leaves the last one alone on its row. The gap beside
        // it is a spacer rather than a stretched cell, so the value still starts under the one
        // above it — what matters for the test is only that nothing is dropped.
        setContent {
            MetaGrid(entries = (1..5).map { MetaEntry(label = "Fact $it", value = "Value $it") })
        }

        composeTestRule.onNodeWithContentDescription("Fact 5, Value 5").assertIsDisplayed()
    }

    @Test
    fun `negative - a heading with no trailing slot draws nothing after the rule`() {
        setContent { SectionHeading(text = "How to") }

        composeTestRule.onNodeWithContentDescription("24 sessions").assertDoesNotExist()
    }
}
