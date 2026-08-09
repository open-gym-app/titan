package com.opengym.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The shell's behaviour, driven through the real graph.
 *
 * These drive [OpenGymApp] itself rather than a stand-in, because every rule under test is a
 * property of the *composition* of the pieces — the bar reads the back stack, the back stack
 * is written by the graph, the graph is assembled from four features' extensions. A test
 * against a hand-built two-destination `NavHost` would pass while the app was broken.
 *
 * Screens are identified by their eyebrow label. The placeholder copy under it belongs to
 * #23–#27 and will change; the eyebrow is the section marker and is stable.
 */
@RunWith(RobolectricTestRunner::class)
class OpenGymAppNavigationTest {

    // The Android flavour of the rule rather than `createComposeRule()`, for the activity: the
    // back gesture is dispatched through its `OnBackPressedDispatcher`, which is what the
    // session flow's guard registers against.
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        composeTestRule.setContent {
            OpenGymTheme {
                OpenGymApp()
            }
        }
    }

    @Test
    fun `opens on Home with the three tabs and a settings action`() {
        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertIsNotSelected()
        composeTestRule.onNodeWithContentDescription(TAB_STATS).assertIsNotSelected()
        composeTestRule.onNodeWithContentDescription(SETTINGS_ACTION).assertIsDisplayed()
    }

    @Test
    fun `tapping a tab shows it and marks it selected`() {
        composeTestRule.onNodeWithContentDescription(TAB_STATS).performClick()

        composeTestRule.onNodeWithText(PROGRESSION).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_STATS).assertIsSelected()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsNotSelected()
    }

    @Test
    fun `a tab stays selected on a screen pushed inside it`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(OPEN_A_SESSION).performClick()

        composeTestRule.onNodeWithText(SESSION_DETAIL).assertIsDisplayed()
        // The detail screen is not the tab's route, only a descendant of its graph — the
        // hierarchy walk in `OpenGymAppState` is what keeps this from clearing the bar.
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertIsSelected()
    }

    @Test
    fun `each tab keeps its own back stack across a switch`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(OPEN_A_SESSION).performClick()
        composeTestRule.onNodeWithText(SESSION_DETAIL).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_STATS).performClick()
        composeTestRule.onNodeWithText(PROGRESSION).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()

        // Not the sessions list: returning to a tab resumes where the user left it. Without
        // `saveState`/`restoreState` this lands on the list and the assertion fails.
        composeTestRule.onNodeWithText(SESSION_DETAIL).assertIsDisplayed()
    }

    @Test
    fun `entering the session flow hides the bottom bar`() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()

        composeTestRule.onNodeWithText(NEW_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(TAB_STATS).assertDoesNotExist()
        // The tab scaffold's top bar goes with it — a workout draws its own chrome.
        composeTestRule.onNodeWithContentDescription(SETTINGS_ACTION).assertDoesNotExist()
    }

    @Test
    fun `the bottom bar stays hidden for every step of the session flow`() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithText(BEGIN).performClick()

        composeTestRule.onNodeWithText(ACTIVE_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()

        composeTestRule.onNodeWithText(FINISH).performClick()

        composeTestRule.onNodeWithText(SUMMARY).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()
    }

    @Test
    fun `leaving the session flow restores the bar and the tab that was open`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(OPEN_A_SESSION).performClick()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).performClick()

        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithText(CANCEL).performClick()

        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()

        // And the Sessions tab still holds the screen it was left on.
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(SESSION_DETAIL).assertIsDisplayed()
    }

    @Test
    fun `finishing the session flow returns to the tab scaffold`() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithText(BEGIN).performClick()
        composeTestRule.onNodeWithText(FINISH).performClick()
        composeTestRule.onNodeWithText(DONE).performClick()

        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
    }

    @Test
    fun `the settings action opens settings outside the tab scaffold`() {
        composeTestRule.onNodeWithContentDescription(SETTINGS_ACTION).performClick()

        composeTestRule.onNodeWithText(SETTINGS).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()

        composeTestRule.onNodeWithText(BACK).performClick()

        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
    }

    @Test
    fun `the back gesture leaves the session flow before the workout starts`() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithText(NEW_SESSION).assertIsDisplayed()

        pressBack()

        // Setup is not a mode: nothing has been logged, so backing out is just backing out.
        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
    }

    @Test
    fun `the back gesture does not abandon an active session`() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithText(BEGIN).performClick()
        composeTestRule.onNodeWithText(ACTIVE_SESSION).assertIsDisplayed()

        pressBack()

        // Unguarded, this pops the last entry in the session graph and the graph with it,
        // dropping the user back on Home with the workout silently discarded.
        composeTestRule.onNodeWithText(ACTIVE_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithText(HOME).assertDoesNotExist()
    }

    @Test
    fun `negative - a tab the user has not opened holds nothing`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()

        // The Sessions tab starts on its list, not on a detail screen left over from
        // another tab's stack.
        composeTestRule.onNodeWithText(SESSION_DETAIL).assertDoesNotExist()
        composeTestRule.onNodeWithText(HISTORY).assertIsDisplayed()
    }

    /**
     * The system back gesture, as the platform delivers it.
     *
     * Through the activity's dispatcher rather than `navController.popBackStack()`, because
     * the thing under test is whether a `BackHandler` intercepts it — and a direct pop would
     * bypass the handler and pass whether or not one exists.
     */
    private fun pressBack() {
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
    }

    private companion object {
        // Bottom-bar items and the settings action are found by the description a screen
        // reader would read, which is also what makes their labelling part of the contract
        // these tests hold.
        const val TAB_HOME = "Home"
        const val TAB_SESSIONS = "Sessions"
        const val TAB_STATS = "Stats"
        const val SETTINGS_ACTION = "Settings"

        // Screen markers — each screen's eyebrow.
        const val HOME = "TODAY"
        const val HISTORY = "HISTORY"
        const val PROGRESSION = "PROGRESSION"
        const val SESSION_DETAIL = "SESSION · 1"
        const val NEW_SESSION = "NEW SESSION"
        const val ACTIVE_SESSION = "ACTIVE · SESSION 1"
        const val SUMMARY = "SUMMARY · SESSION 1"
        const val SETTINGS = "SETTINGS"

        // Actions.
        const val START_SESSION = "START SESSION"
        const val OPEN_A_SESSION = "OPEN A SESSION"
        const val BEGIN = "BEGIN"
        const val FINISH = "FINISH"
        const val DONE = "DONE"
        const val CANCEL = "CANCEL"
        const val BACK = "BACK"
    }
}
