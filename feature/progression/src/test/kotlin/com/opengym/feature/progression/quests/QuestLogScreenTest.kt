package com.opengym.feature.progression.quests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quest log, driven by hand-built state.
 *
 * The stateless half, per CONTRIBUTING.md § Screens — no ViewModel and no Hilt graph. What is
 * asserted here is what the screen *draws*; whether a quest's progress figure is right is
 * `QuestProgressUseCaseTest`'s question, and whether the rank table maps a level correctly is
 * `LevelTitlesTest`'s.
 *
 * The three states are the three #104 asks for, and they come from the same fixtures the
 * `@Preview`s render.
 */
@RunWith(RobolectricTestRunner::class)
// A phone's worth of window: Robolectric's default is 320x470dp, on which this screen is one long
// scroll and every assertion becomes a scroll first.
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class QuestLogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ─── Populated ──────────────────────────────────────────────────────────────────────

    /**
     * The level *digit* is deliberately not asserted here, in this test or the two below it.
     * [com.opengym.core.ui.chart.ProgressRing] clears its own subtree and replaces it with one
     * sentence — `LVL` and `13` as two nodes are two swipes for one fact — so the figure inside
     * the ring is not a node to find. The sentence is asserted instead, and it carries the level.
     */
    @Test
    fun `the hero shows the level, its rank and the xp into it`() {
        setContent(populatedQuestLog())

        composeTestRule.onNodeWithText("Steel Forged").assertIsDisplayed()
        // 1,240 total; level 13 opens at 1,200, and a level costs 100.
        composeTestRule.onNodeWithText("40 / 100 XP").assertIsDisplayed()
    }

    @Test
    fun `the ring is announced as one sentence with its percentage`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Level 13, 40 / 100 XP, 40% complete")
            .assertIsDisplayed()
    }

    @Test
    fun `a quest under way shows its counts and its reward`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Break five personal records, 2 / 5, worth 300 XP")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a completed quest is announced as complete and reports what it earned`() {
        setContent(populatedQuestLog())

        // Three sessions met the three-session quest.
        composeTestRule
            .onNodeWithContentDescription("Train three times, complete, earned 60 XP")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the board is split into this week and this month`() {
        setContent(populatedQuestLog())

        composeTestRule.onNodeWithText("This week").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("This month").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the achievements grid counts what is held against the catalogue`() {
        setContent(populatedQuestLog())

        // Three of the six.
        composeTestRule.onNodeWithText("3 / 6").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a locked achievement is drawn and says only that it is locked`() {
        setContent(populatedQuestLog())

        // Present, so a user can see what there is to earn — and announced as locked, with
        // nothing about the threshold behind it. See `AchievementGrid`.
        composeTestRule
            .onNodeWithContentDescription("A month-long streak, locked")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `an unlocked achievement is announced as unlocked`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("First workout logged, unlocked")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a live streak shows its length against the best`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("6 days, Best: 21 days")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a streak that ties the best says so instead of repeating the figure`() {
        // "3 days, best 3" is two figures the reader has to compare to find the fact.
        setContent(justLevelledUpQuestLog())

        composeTestRule
            .onNodeWithContentDescription("3 days, Your best yet")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ─── Zero state ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a brand-new user is level one with an empty ring`() {
        setContent(emptyQuestLog())

        composeTestRule.onNodeWithText("Newcomer").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Level 1, 0 / 100 XP, 0% complete")
            .assertIsDisplayed()
    }

    @Test
    fun `a brand-new user is invited to start a streak rather than shown a zero`() {
        setContent(emptyQuestLog())

        composeTestRule.onNodeWithText("No streak yet").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Train today to start one.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a brand-new user sees the whole board at zero rather than an empty section`() {
        setContent(emptyQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Train three times, 0 / 3, worth 60 XP")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Move 80 tonnes, 0 / 80,000, worth 280 XP")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a brand-new user sees every achievement, all locked`() {
        setContent(emptyQuestLog())

        composeTestRule.onNodeWithText("0 / 6").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("First workout logged, locked")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ─── Just levelled up ───────────────────────────────────────────────────────────────

    @Test
    fun `a level just reached draws the new rank and a nearly empty ring`() {
        // The state a level-up leaves behind. There is no transient highlight on this screen —
        // the crossing is celebrated on the session summary, where it is actually observed — so
        // what matters is that the ring resets rather than carrying the previous level's fill.
        setContent(justLevelledUpQuestLog())

        composeTestRule.onNodeWithText("Iron Disciple").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Level 5, 5 / 100 XP, 5% complete")
            .assertIsDisplayed()
    }

    @Test
    fun `the lifetime total is drawn beside the level's own progress`() {
        // Two different figures, and the screen shows both: 5 points into level 5, 405 in total.
        setContent(justLevelledUpQuestLog())

        composeTestRule.onNodeWithText("405 XP earned in total").assertIsDisplayed()
    }

    private fun setContent(state: QuestLogUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                QuestLogScreen(state = state, onBack = {})
            }
        }
    }
}
