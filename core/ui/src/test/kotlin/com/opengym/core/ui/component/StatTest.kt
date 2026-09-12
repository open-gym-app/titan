package com.opengym.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.opengym.core.domain.model.ExerciseStats
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stat tile, chip and PR badge.
 *
 * Two things are being checked and they are easy to conflate: what is *drawn* (found with
 * `onNodeWithText`) and what is *announced* (found with `onNodeWithContentDescription`).
 * Both components deliberately replace their semantics with a single spoken phrase, so a
 * regression that split them back into two nodes fails here rather than being noticed by
 * somebody using TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
class StatTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val stats = SessionStats(
        totalSets = 12,
        totalVolume = 4_820_000_000,
        durationSeconds = 3_144,
        exerciseBreakdown = List(8) { index ->
            ExerciseStats(sessionExerciseId = index + 1L, totalSets = 3, totalVolume = 600_000_000)
        },
    )

    @Test
    fun `a tile announces its label and figure as one phrase`() {
        setContent { StatTile(label = "Volume", value = "4,820", unit = "kg") }

        composeTestRule.onNodeWithContentDescription("Volume, 4,820 kg").assertIsDisplayed()
    }

    @Test
    fun `a tile with no unit announces the figure alone`() {
        setContent { StatTile(label = "Duration", value = "0:52:24") }

        composeTestRule.onNodeWithContentDescription("Duration, 0:52:24").assertIsDisplayed()
    }

    @Test
    fun `a chip announces its label after the figure it qualifies`() {
        setContent { StatChip(value = "12", label = "sets") }

        // The drawn order, so the spoken chip is the one on the screen: a Voice Access user
        // says "12 sets", and Accessibility Scanner holds the label to the visible text.
        composeTestRule.onNodeWithContentDescription("12 sets").assertIsDisplayed()
    }

    @Test
    fun `a PR badge spells out what the abbreviation stands for`() {
        setContent { PrBadge(type = PrType.ESTIMATED_1RM) }

        composeTestRule.onNodeWithContentDescription("PR 1RM, personal record").assertIsDisplayed()
    }

    @Test
    fun `a PR badge with no kind still announces what it is`() {
        setContent { PrBadge() }

        composeTestRule.onNodeWithContentDescription("PR, personal record").assertIsDisplayed()
    }

    @Test
    fun `the summary grid counts the exercises that contributed a set`() {
        setContent { SessionStatGrid(stats = stats) }

        composeTestRule.onNodeWithContentDescription("Exercises, 8 done").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Sets, 12 total").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Volume, 4,820 kg").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Duration, 0:52:24").assertIsDisplayed()
    }

    @Test
    fun `the grid renders the volume in the ambient unit system`() {
        composeTestRule.setContent {
            OpenGymTheme {
                CompositionLocalProvider(LocalUnitSystem provides UnitSystem.IMPERIAL) {
                    SessionStatGrid(stats = stats)
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Volume, 10,626 lb").assertIsDisplayed()
    }

    @Test
    fun `negative - the chip row does not carry a tile's unit qualifier`() {
        setContent { SessionStatChips(stats = stats) }

        composeTestRule.onNodeWithContentDescription("Sets, 12 total").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("12 sets").assertIsDisplayed()
    }

    @Test
    fun `an announcing chip row is one live region saying the counter and the volume`() {
        setContent { SessionStatChips(stats = stats, showDuration = false, announces = true) }

        // One node, not two chips: a set logged elsewhere on the screen moves both figures at
        // once, and a polite live region says the sentence once when they do. TalkBack hears
        // nothing of a value that changes without a tap unless something says it is live.
        composeTestRule
            .onNodeWithContentDescription("12 sets · 4,820 kg")
            .assertIsDisplayed()
            .assert(expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeTestRule.onNodeWithContentDescription("12 sets").assertDoesNotExist()
    }

    @Test
    fun `negative - a chip row in a list is not live`() {
        setContent { SessionStatChips(stats = stats) }

        // Two separate chips, and neither announces changes: a finished session's figures do
        // not change, and a list of forty rows each declaring a live region is forty regions.
        composeTestRule
            .onNodeWithContentDescription("12 sets")
            .assert(isNotLive())
    }

    private fun isNotLive() =
        SemanticsMatcher("has no live region") { node -> SemanticsProperties.LiveRegion !in node.config }

    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent { OpenGymTheme(content = content) }
    }
}
