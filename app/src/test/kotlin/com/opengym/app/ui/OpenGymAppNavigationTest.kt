package com.opengym.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.opengym.app.HiltTestActivity
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.ui.theme.OpenGymTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

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

    /**
     * The stores the session flow reads, so there is a workout for it to start.
     *
     * The flow ends in real rows — `workout_session` plus a `session_exercise` per exercise,
     * whose `exercise_id` is a foreign key — so a session cannot be started against an empty
     * database. That is the right constraint, and it means these tests seed one exercise and
     * one template rather than fake either repository.
     *
     * A **template** rather than the exercise picker, even though both reach the same place:
     * the picker draws the whole catalogue, and the seeded one is 800 rows deep by the time
     * this runs, which makes finding a particular row in it a test about `LazyColumn`
     * recycling. One template row sits at the top of the screen and is one tap.
     */
    @Inject
    lateinit var exercises: ExerciseRepository

    @Inject
    lateinit var templates: TemplateRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            val benchPress = exercises.insert(Exercise(name = BENCH_PRESS))
            templates.insert(
                template = WorkoutTemplate(name = TEMPLATE),
                exercises = listOf(TemplateExerciseDraft(exerciseId = benchPress)),
            )
        }
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
        // 873-row list for the user to hunt for.
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
    fun `opening a logged session leaves the tab scaffold, and back returns to it`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(OPEN_A_SESSION).performClick()

        // A logged session opens the recap `:feature:session` draws, in its historical mode —
        // the same screen a workout ends on. It is registered outside the tabs, like the
        // exercise detail and the plate calculator, so it takes the whole screen and draws its
        // own way back.
        composeTestRule.onNodeWithContentDescription(SESSION_RECAP).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(SESSION_RECAP).performClick()

        composeTestRule.onNodeWithText(HISTORY).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertIsSelected()
    }

    /*
     * There is no test here for a screen pushed *inside* a tab keeping that tab selected, and
     * none for such a screen surviving a tab switch. Nothing in the app is one any more: the
     * session recap was the last, and #27 moved it out to the top level alongside the exercise
     * detail. The rules those tests covered are still in `OpenGymAppState` and are still
     * exercised — `saveState`/`restoreState` by the tab-state test below, the reselection
     * inversion by the library's re-tap test above — and #24 gives the Sessions tab three
     * destinations of its own to put the hierarchy walk back under test.
     */

    @Test
    fun `each tab keeps its own state across a switch`() {
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()
        composeTestRule.onNodeWithContentDescription(LIBRARY_SEARCH).performClick()
        composeTestRule.onNodeWithText(LIBRARY_SEARCH_PLACEHOLDER).performTextInput(QUERY)

        composeTestRule.onNodeWithContentDescription(TAB_STATS).performClick()
        composeTestRule.onNodeWithText(PROGRESSION).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()

        // Not a fresh library: returning to a tab resumes where the user left it. Without
        // `saveState`/`restoreState` the entry is rebuilt, the search box closes, and this
        // assertion fails — which is the same mechanism a pushed screen would have exercised.
        composeTestRule.onNodeWithText(QUERY).assertIsDisplayed()
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
        startAWorkout()

        composeTestRule.onNodeWithText(ACTIVE_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()

        composeTestRule.onNodeWithText(FINISH).performClick()

        // The recap is the end of the flow rather than a step of it — it is pushed as the flow
        // is popped — but it is still bar-less, because it is outside the tab scaffold.
        composeTestRule.onNodeWithText(DONE).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()
    }

    /*
     * The other half of the empty-session path — ticking an exercise and confirming it — is
     * not driven here, and it is the half that was broken in the real app: the picker's answer
     * was read as the graph was built rather than inside the destination, so a confirmed
     * selection never reached the flow that opened it.
     *
     * It cannot be driven from this test yet. `ExerciseLibraryScreen`'s state is a combine over
     * four queries, and under Robolectric it never leaves its initial value — the picker draws
     * its chrome and no rows, whatever it is given and however long it is waited on. The tab's
     * own test above passes only because "0 EXERCISES" is what a library that has not loaded
     * also says. Whatever stalls it is older than this flow and is nothing to do with the
     * picker's result; until it is fixed there is no row here to tick.
     */

    @Test
    fun `backing out of the picker leaves the quick start usable`() {
        openThePicker()
        composeTestRule.waitUntil {
            composeTestRule.onAllNodesWithText(CONFIRM_NONE).fetchSemanticsNodes().isNotEmpty()
        }

        pressBack()

        // A cancel brings no answer back with it, so the screen cannot learn the picker is
        // gone from one arriving — it learns it from being on top again. Without that, the
        // flow still believes the picker is up, and the action that opens it is the one it
        // disables while that is true: the empty-session path is dead for the rest of the
        // flow's life, and for a user with no templates the screen has nothing else on it.
        composeTestRule.onNodeWithText(NEW_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY_SESSION).assertIsEnabled()

        composeTestRule.onNodeWithText(EMPTY_SESSION).performClick()
        composeTestRule.waitUntil {
            composeTestRule.onAllNodesWithText(CONFIRM_NONE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `leaving the session flow restores the bar and the tab that was open`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).performClick()

        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithContentDescription(LEAVE_SETUP).performClick()

        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()

        // And the tab the user had been on is still there to go back to.
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(HISTORY).assertIsDisplayed()
    }

    @Test
    fun `finishing the session flow returns to the tab scaffold`() {
        startAWorkout()
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
        startAWorkout()
        composeTestRule.onNodeWithText(ACTIVE_SESSION).assertIsDisplayed()

        pressBack()

        // Unguarded, this pops the last entry in the session graph and the graph with it,
        // dropping the user back on Home with the workout silently discarded.
        composeTestRule.onNodeWithText(ACTIVE_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithText(HOME).assertDoesNotExist()
    }

    @Test
    fun `the blocked back gesture on an active session says why`() {
        startAWorkout()
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
     * Home to a running workout, the way a user gets there.
     *
     * Four taps through three screens in two feature modules, which is exactly why it is
     * worth driving here: nothing but the assembled graph can tell whether the picker's
     * result reaches the flow that opened it.
     */
    private fun startAWorkout() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()
        // The template list is a real Room query on a real background dispatcher, and
        // Compose's idling does not track it — so the row is waited for, not assumed.
        composeTestRule.waitUntil {
            composeTestRule.onAllNodes(templateRow()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(templateRow()).performClick()
        // And again for the review panel: reading the template out and resolving its
        // exercises against the catalogue are two more queries off the main thread.
        composeTestRule.waitUntil {
            composeTestRule.onAllNodesWithText(START_WORKOUT).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(START_WORKOUT).performClick()
        // Once more for the commit: the session and its exercises are two writes, and the
        // navigation into the workout follows the state they produce.
        composeTestRule.waitUntil {
            composeTestRule.onAllNodesWithText(ACTIVE_SESSION).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Home to the exercise picker, by the quick-start action rather than a template. */
    private fun openThePicker() {
        composeTestRule.onNodeWithText(START_SESSION).performClick()
        composeTestRule.onNodeWithText(EMPTY_SESSION).performClick()
    }

    /**
     * The seeded template's row, by the sentence it speaks.
     *
     * A substring match on the name: the rest of the description is the derived exercise
     * count and duration estimate, which belong to `TemplateRow`'s own tests.
     */
    private fun templateRow() = hasContentDescription(TEMPLATE, substring = true)

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
        const val NEW_SESSION = "New session"
        const val ACTIVE_SESSION = "ACTIVE · SESSION 1"

        /*
         * The recap, by the chrome each of its two modes draws.
         *
         * Its *body* is not a marker here, and cannot be: the screen assembles it from a
         * combine over three Room queries, and under Robolectric those never leave their
         * initial value — the same thing that stops the exercise picker drawing rows in the
         * test below. What each mode draws without reading anything is the marker instead:
         * the historical view's back arrow, and the just-finished view's DONE. Both are also
         * the property under test in the two places they are used, which is why that is not a
         * compromise — whether the recap renders a session is `SessionSummaryScreenTest`'s
         * question, over a state built by hand.
         */
        const val SESSION_RECAP = "Back to the session list"

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
        const val FINISH = "FINISH"
        const val DONE = "Done"

        // The new-session flow: the seeded template, and the commit that ends setup.
        const val TEMPLATE = "Push day"
        const val BENCH_PRESS = "Bench press"
        const val START_WORKOUT = "Start workout"

        // The other way into the flow — the quick start — and the picker's confirm bar, which
        // is the picker's marker here because it is drawn whether or not the list under it has
        // loaded.
        const val EMPTY_SESSION = "Empty session"
        const val CONFIRM_NONE = "Select exercises"

        // Navigation icons, by the description a screen reader reads. The new-session bar's
        // arrow says which of its two meanings it carries, so it gets its own name.
        const val BACK = "Back"
        const val LEAVE_SETUP = "Leave without starting"

        // Messages. Asserted on the copy the user reads, since that is the whole point of it.
        const val BACK_BLOCKED = "Finish the session to leave"
    }
}
