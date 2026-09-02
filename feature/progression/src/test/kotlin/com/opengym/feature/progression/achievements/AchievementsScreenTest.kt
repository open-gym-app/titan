package com.opengym.feature.progression.achievements

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
 * The achievements grid, driven by hand-built state with no ViewModel and no Hilt graph, per
 * CONTRIBUTING.md § Screens.
 *
 * These cases were `QuestLogScreenTest`'s while the grid lived at the bottom of the board. They
 * moved here with the screen and are unchanged in substance: a cell for every catalogue entry, a
 * locked one saying only that it is locked, an unlocked one announced as such, and a correct
 * total over both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AchievementsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the grid counts what is held against the catalogue`() {
        setContent(populatedAchievements())

        // Three of the six.
        composeTestRule.onNodeWithText("3 / 6").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a locked achievement is drawn and says only that it is locked`() {
        setContent(populatedAchievements())

        // Present, so a user can see what there is to earn — and announced as locked, with
        // nothing about the threshold behind it. See `AchievementGrid`.
        composeTestRule
            .onNodeWithContentDescription("A month-long streak, locked")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `an unlocked achievement is announced as unlocked`() {
        setContent(populatedAchievements())

        composeTestRule
            .onNodeWithContentDescription("First workout logged, unlocked")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a brand-new user sees every achievement, all locked`() {
        setContent(emptyAchievements())

        composeTestRule.onNodeWithText("0 / 6").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("First workout logged, locked")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the screen says what a milestone is, so it is not mistaken for a quest`() {
        // The whole reason these left the quest board: the two were drawn as one idea and are
        // not one idea. See `AchievementsScreen`.
        setContent(populatedAchievements())

        composeTestRule
            .onNodeWithText("Permanent unlocks. Unlike quests, they never reset.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setContent(state: AchievementsUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                AchievementsScreen(state = state, onBack = {})
            }
        }
    }
}
