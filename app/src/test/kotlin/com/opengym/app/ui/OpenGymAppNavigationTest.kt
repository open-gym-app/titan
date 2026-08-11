package com.opengym.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.opengym.app.HiltTestActivity
import com.opengym.core.ui.theme.OpenGymTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's behaviour, driven through the real graph.
 *
 * These drive [OpenGymApp] itself rather than a stand-in, because every rule under test is a
 * property of the *composition* of the pieces — the bar reads the back stack, the back stack
 * is written by the graph, the graph is assembled from four features' extensions. A test
 * against a hand-built two-destination `NavHost` would pass while the app was broken.
 *
 * Screens are identified by their eyebrow label, or by their title where they have a real top
 * app bar. The placeholder copy under it belongs to #23–#27 and will change; the marker is
 * stable.
 *
 * ### Why this one needs Hilt
 *
 * The graph now contains a real screen — settings — and `hiltViewModel()` resolves its factory
 * through the activity, which therefore has to be `@AndroidEntryPoint`. That makes the whole
 * test a Hilt test: `HiltTestApplication` for the component, [HiltTestActivity] for the entry
 * point, and the real bindings behind both. Nothing is faked, so what settings renders here is
 * what it renders on a device, reading a real (empty) database and a real preferences file.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class OpenGymAppNavigationTest {

    // Order matters: the Hilt rule has to have built the component before the activity the
    // compose rule launches asks it for one.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    // The Android flavour of the rule rather than `createComposeRule()`, for the activity: the
    // back gesture is dispatched through its `OnBackPressedDispatcher`, which is what the
    // session flow's guard registers against.
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() {
        hilt.inject()
        composeTestRule.setContent {
            OpenGymTheme {
                OpenGymApp()
            }
        }
    }

    @Test
    fun `opens on Home with the four tabs and a settings action`() {
        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertIsNotSelected()
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).assertIsNotSelected()
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
    fun `the library is a tab, and keeps the bar rather than a way back`() {
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()

        // The real screen over a real (empty) database, so the count is zero and the empty
        // state is what it draws — both of which are the library and nothing else.
        composeTestRule.onNodeWithText(LIBRARY_EMPTY_COUNT).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).assertIsSelected()

        // The two properties that make it a tab rather than the push it used to be: the bar
        // it was reached by is still there to leave by, and there is no back arrow above a
        // 184-row list for the user to hunt for.
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(BACK).assertDoesNotExist()
    }

    @Test
    fun `re-tapping the library tab rebuilds it, which is what puts it back to the top`() {
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()
        composeTestRule.onNodeWithContentDescription(LIBRARY_SEARCH).performClick()
        composeTestRule.onNodeWithText(LIBRARY_SEARCH_PLACEHOLDER).performTextInput(QUERY)
        composeTestRule.onNodeWithText(QUERY).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()

        // The library's root *is* its tab, so there is nothing to pop back to and the re-tap
        // would look inert. What it actually does is drop the entry and push a fresh one, so
        // the screen is rebuilt with no scroll position to restore — which is what puts a
        // long list back at the top. The search box closing on what was typed in it is the
        // visible half of that same fact.
        composeTestRule.onNodeWithText(QUERY).assertDoesNotExist()
        composeTestRule.onNodeWithText(LIBRARY_SEARCH_PLACEHOLDER).assertDoesNotExist()
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
    fun `re-tapping the active tab returns it to its root`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(OPEN_A_SESSION).performClick()
        composeTestRule.onNodeWithText(SESSION_DETAIL).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()

        // The one thing pressing the tab you are already on can usefully do.
        composeTestRule.onNodeWithText(HISTORY).assertIsDisplayed()
        composeTestRule.onNodeWithText(SESSION_DETAIL).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertIsSelected()
    }

    @Test
    fun `a tab reset by a re-tap stays reset after switching away and back`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(OPEN_A_SESSION).performClick()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()

        composeTestRule.onNodeWithContentDescription(TAB_HOME).performClick()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()

        // The reset has to drop the saved stack, not just skip restoring it. Saving on a
        // re-tap files the detail screen away and this visit silently restores it — the same
        // bug as not resetting at all, delayed by one tab switch.
        composeTestRule.onNodeWithText(HISTORY).assertIsDisplayed()
        composeTestRule.onNodeWithText(SESSION_DETAIL).assertDoesNotExist()
    }

    @Test
    fun `entering the session flow hides the bottom bar`() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()

        composeTestRule.onNodeWithText(NEW_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).assertDoesNotExist()
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

        // Settings draws its own bar, so its title is the marker — and the tab bar is gone,
        // which is the property under test: settings is a place you leave to, not a tab.
        composeTestRule.onNodeWithText(SETTINGS_GROUP_APPEARANCE).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(BACK).performClick()

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
    fun `the blocked back gesture on an active session says why`() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithText(BEGIN).performClick()
        // Not decoration: the guard belongs to the active-session entry and is only armed
        // once that entry is resumed, which is after the transition into it has finished.
        // Pressing back before this assertion presses it at the previous screen.
        composeTestRule.onNodeWithText(ACTIVE_SESSION).assertIsDisplayed()

        pressBack()

        // Swallowing the press is the guard; saying so is what keeps it from reading as a
        // frozen app, since FINISH is the only other way out until #26.
        composeTestRule.onNodeWithText(BACK_BLOCKED).assertIsDisplayed()
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
        const val TAB_LIBRARY = "Library"
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

        // Settings' first group header, uppercased by the eyebrow style.
        const val SETTINGS_GROUP_APPEARANCE = "APPEARANCE"

        // The library's header count. Zero here: the seed does not run against the test
        // database, and what the tab draws over an empty catalogue is still the tab.
        const val LIBRARY_EMPTY_COUNT = "0 EXERCISES"

        // The library's search button, the box it opens, and something to type into it.
        const val LIBRARY_SEARCH = "Search"
        const val LIBRARY_SEARCH_PLACEHOLDER = "Search exercises…"
        const val QUERY = "bench"

        // Actions.
        const val START_SESSION = "START SESSION"
        const val OPEN_A_SESSION = "OPEN A SESSION"
        const val BEGIN = "BEGIN"
        const val FINISH = "FINISH"
        const val DONE = "DONE"
        const val CANCEL = "CANCEL"

        // Settings' navigation icon, by the description a screen reader reads.
        const val BACK = "Back"

        // Messages. Asserted on the copy the user reads, since that is the whole point of it.
        const val BACK_BLOCKED = "Finish the session to leave"
    }
}
