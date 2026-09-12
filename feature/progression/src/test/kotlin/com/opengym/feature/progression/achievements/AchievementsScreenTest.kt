package com.opengym.feature.progression.achievements

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
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
 * The achievements shelf, driven by hand-built state with no ViewModel and no Hilt graph, per
 * CONTRIBUTING.md § Screens.
 *
 * The first cases were `QuestLogScreenTest`'s while the grid lived at the bottom of the board.
 * They moved here with the screen and are unchanged in substance: a medal for every catalogue
 * entry, a locked one saying only that it is locked, an unlocked one announced as such, and a
 * correct total over both.
 *
 * What the medals added is asserted on the sentence a screen reader is given rather than on the
 * drawing: the metal a milestone is struck in, the date it was earned, and the fact that a held
 * medal can be struck again. The pixels are `AchievementsScreenPreview`'s job.
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
    fun `an unlocked achievement is announced with its metal and the day it was earned`() {
        // The metal is spoken because the medal draws the rank in colour, and a rank carried by
        // colour alone is a rank half the readers lose. See `MedalShelf`.
        setContent(populatedAchievements())

        composeTestRule
            .onNodeWithContentDescription("First workout logged, unlocked, Bronze, earned Feb 14")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the top rung of a ladder is gold and the middle one silver`() {
        // Ten sessions sits between one and a hundred; a hundred is the top of its ladder. Both
        // are read off the catalogue rather than written down — see `Achievement.tier`.
        setContent(AchievementsUiState(entries = unlocked("ten_sessions", "hundred_sessions"), isLoading = false))

        composeTestRule
            .onNodeWithContentDescription("Ten workouts in, unlocked, Silver, earned Feb 14")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("One hundred workouts, unlocked, Gold, earned Feb 15")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the screen leads with the newest medal`() {
        // `populatedAchievements` dates its three a day apart in the order they are named, so the
        // first record is the most recent of them. See `AchievementsUiState.latest`.
        setContent(populatedAchievements())

        composeTestRule
            .onNodeWithContentDescription("Latest medal, First personal record, unlocked, Bronze, earned Feb 16")
            .assertIsDisplayed()
    }

    @Test
    fun `a held medal can be struck again`() {
        // The one thing on this screen that is only fun. It changes nothing, which is why the
        // action carries a label rather than being an unnamed click on a decoration.
        setContent(populatedAchievements())

        composeTestRule
            .onNodeWithContentDescription("First workout logged, unlocked, Bronze, earned Feb 14")
            .performScrollTo()
            .assertHasClickAction()
    }

    @Test
    fun `a locked medal is not pressable`() {
        setContent(populatedAchievements())

        composeTestRule
            .onNodeWithContentDescription("A month-long streak, locked")
            .performScrollTo()
            .assertHasNoClickAction()
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
    fun `a user with no medals is told what strikes the first one`() {
        // The hero's place is filled rather than left out: the screen is not empty, and a blank
        // where the newest medal goes would read as one. See `NoMedalsYetCard`.
        setContent(emptyAchievements())

        composeTestRule
            .onNodeWithContentDescription("No medals yet. Finish a workout and the first one is struck.")
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

    @Test
    fun `nothing is drawn until the read has answered`() {
        // The default state, which is what the ViewModel emits before the store answers. A
        // shelf drawn from it would tell a lifter holding five medals that they hold none, and
        // then strike their newest one again on the way to the truth. See `AchievementsScreen`.
        setContent(AchievementsUiState())

        composeTestRule.onNodeWithText("0 / 0").assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription("No medals yet. Finish a workout and the first one is struck.")
            .assertDoesNotExist()
    }

    private fun setContent(state: AchievementsUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                AchievementsScreen(state = state, onBack = {})
            }
        }
    }
}
