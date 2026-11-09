package com.titan.feature.progression.quests

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
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
     * [com.titan.core.ui.chart.ProgressRing] clears its own subtree and replaces it with one
     * sentence — `LVL` and `40` as two nodes are two swipes for one fact — so the figure inside
     * the ring is not a node to find. The sentence is asserted instead, and it carries the level.
     */
    @Test
    fun `the hero shows the level, its rank and the xp into it`() {
        setContent(populatedQuestLog())

        composeTestRule.onNodeWithText("Steel Forged").assertIsDisplayed()
        // 14,853 total; level 40 opens at 14,613, and costs 566.
        composeTestRule.onNodeWithText("240 / 566 XP").assertIsDisplayed()
    }

    @Test
    fun `the ring is announced as one sentence with its percentage`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Level 40, 240 / 566 XP, 42% complete")
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
    fun `the board opens on this week, with its own score`() {
        setContent(populatedQuestLog())

        // Two of the four weekly quests are banked, leaving 100 + 70 XP reachable. The cadence
        // card speaks as one node — see `QuestPeriodHeader`.
        composeTestRule.onNodeWithText("THIS WEEK").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("This week, 2 of 4 cleared, 170 XP still to earn")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the month is a tab of its own, composed but not on screen`() {
        setContent(populatedQuestLog())

        // `assertExists` rather than `assertIsDisplayed`: the cadences are pages of a pager and
        // only one is on screen at a time. What this pins is that the second page is *composed*
        // — `beyondViewportPageCount = 1`, so the pager's height is the taller of the two and a
        // swipe does not resize the screen under the thumb making it. See `QuestSections`.
        composeTestRule.onNodeWithText("THIS MONTH").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("This month, 0 of 3 cleared, 830 XP still to earn")
            .assertExists()
    }

    @Test
    fun `a cleared quest leaves the live group for the cleared one`() {
        setContent(populatedQuestLog())

        // The board leads with what is left to do, and says what has been banked underneath.
        // A cleared row keeps its name and its reward and loses its bar — see `ClearedQuestRow`.
        composeTestRule
            .onNodeWithText("Cleared · 2")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Train three times, complete, earned 60 XP")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a quest still open keeps its counts and its reward`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Train five times, 3 / 5, worth 100 XP")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `there is no cleared group on a board with nothing banked`() {
        // A heading over nothing would be the screen listing what the user has not done.
        setContent(emptyQuestLog())

        composeTestRule.onAllNodesWithText("Cleared · 0").assertCountEquals(0)
    }

    @Test
    fun `the achievements are one row away, counted`() {
        setContent(populatedQuestLog())

        // Three of the six, on a link rather than a grid: an achievement is a permanent unlock
        // with nothing to act on today, so it is a screen away. See `AchievementsScreen`.
        composeTestRule
            .onNodeWithContentDescription("Achievements, everything you have unlocked: 3 of 6")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `tapping the achievements row asks for the grid`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Achievements, everything you have unlocked: 3 of 6")
            .performScrollTo()
            .performClick()

        assertEquals(1, openedAchievements)
    }

    @Test
    fun `a live streak shows its length against the best`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("6 weeks, Best: 21 weeks")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a streak that ties the best says so instead of repeating the figure`() {
        // "3 weeks, best 3" is two figures the reader has to compare to find the fact.
        setContent(justLevelledUpQuestLog())

        composeTestRule
            .onNodeWithContentDescription("3 weeks, Your best yet")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ─── Zero state ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a brand-new user is level one with an empty ring`() {
        setContent(emptyQuestLog())

        composeTestRule.onNodeWithText("Newcomer").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Level 1, 0 / 60 XP, 0% complete")
            .assertIsDisplayed()
    }

    @Test
    fun `a brand-new user is invited to start a streak rather than shown a zero`() {
        setContent(emptyQuestLog())

        composeTestRule.onNodeWithText("No streak yet").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Meet your weekly goal to start one.")
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
    fun `a brand-new user is told they hold no achievements yet`() {
        setContent(emptyQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Achievements, everything you have unlocked: 0 of 6")
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
            .onNodeWithContentDescription("Level 20, 5 / 398 XP, 1% complete")
            .assertIsDisplayed()
    }

    @Test
    fun `the lifetime total is drawn beside the level's own progress`() {
        // Two different figures, and the screen shows both: 5 points into level 20, 4,974 in total.
        setContent(justLevelledUpQuestLog())

        composeTestRule.onNodeWithText("4,974 XP earned in total").assertIsDisplayed()
    }

    // ─── Streak protection ──────────────────────────────────────────────────────────────

    @Test
    fun `a spent allowance says it is gone rather than drawing a zero`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Streak protection, Your 1 streak protection is gone for this month")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a brand-new user is shown a full allowance beside their absent streak`() {
        // Drawn in both streak states: a user with no streak still has an allowance, and it is
        // what keeps the run they are about to start from being lost to one short week.
        setContent(emptyQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Streak protection, 1 of 1 streak protection left this month")
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ─── The week against the goal ──────────────────────────────────────────────────────

    @Test
    fun `the week in progress is counted against the goal`() {
        setContent(populatedQuestLog())

        composeTestRule.onNodeWithText("2 of 4 workouts this week").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a week that has met its goal says so`() {
        setContent(justLevelledUpQuestLog())

        composeTestRule.onNodeWithText("Weekly goal met").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a brand-new user is shown the goal a first week has to meet`() {
        setContent(emptyQuestLog())

        composeTestRule.onNodeWithText("0 of 4 workouts this week").performScrollTo().assertIsDisplayed()
    }

    // ─── Recent loot ────────────────────────────────────────────────────────────────────

    @Test
    fun `a drop is drawn with its rarity and what produced it`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Pet egg, Rare, Finished workout, Sep 2")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `every recent drop is listed, commonest included`() {
        setContent(populatedQuestLog())

        composeTestRule
            .onNodeWithContentDescription("Lifting strap, Uncommon, Finished workout, Aug 30")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Chalk block, Common, Finished workout, Aug 28")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a user with no drops gets no loot section at all`() {
        // Absent rather than empty: a heading over a blank would promise a thing the user cannot
        // go and do, since a drop is rolled by the app rather than earned on purpose.
        setContent(emptyQuestLog())

        composeTestRule.onAllNodesWithText("RECENT LOOT").assertCountEquals(0)
    }

    private var openedAchievements = 0

    private fun setContent(state: QuestLogUiState) {
        composeTestRule.setContent {
            TitanTheme {
                QuestLogScreen(state = state, onBack = {}, onOpenAchievements = { openedAchievements++ })
            }
        }
    }
}
