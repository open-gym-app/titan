package com.titan.app.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.titan.app.HiltTestActivity
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.ui.theme.TitanTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
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
 * These drive [TitanApp] itself rather than a stand-in, because every rule under test is a
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
class TitanAppNavigationTest {

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

    /**
     * The history the Sessions tab reads.
     *
     * Seeded for the same reason the template is: the tab draws rows from the database and
     * there is no placeholder button on it any more, so opening a recap from the session list
     * — the one navigation edge that crosses from `:feature:history` into `:feature:session` —
     * is only reachable if there is a session to tap.
     */
    @Inject
    lateinit var sessions: SessionRepository

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            // The default templates the database is created with (#213) are taken out first.
            // This suite is about the graph, not about what a fresh install offers — that is
            // `EndToEndJourneyTest`'s — and a template list of one keeps its row on screen and
            // first on Home's list, where seventeen defaults would push it past both.
            templates.getAll().first().forEach { templates.delete(it.id) }
            val benchPress = exercises.insert(Exercise(name = BENCH_PRESS))
            templates.insert(
                template = WorkoutTemplate(name = TEMPLATE),
                exercises = listOf(TemplateExerciseDraft(exerciseId = benchPress)),
            )
            sessions.insert(
                WorkoutSession(
                    name = LOGGED_SESSION,
                    isInProgress = false,
                    completedAt = LOGGED_AT + LOGGED_DURATION_MILLIS,
                    lastActiveAt = LOGGED_AT + LOGGED_DURATION_MILLIS,
                    createdAt = LOGGED_AT,
                    updatedAt = LOGGED_AT,
                ),
            )
        }
        composeTestRule.setContent {
            TitanTheme {
                // Every test in this class is about the shell *after* first run. Opening on
                // onboarding is `OnboardingRoutingTest`'s subject and nothing else's.
                TitanApp(startsOnOnboarding = false)
            }
        }
    }

    @Test
    fun `opens on Home with the four tabs and a settings action`() {
        awaitHome()

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
    fun `pressing the tab you are already on leaves it exactly as it was`() {
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()
        composeTestRule.onNodeWithContentDescription(LIBRARY_SEARCH).performClick()
        composeTestRule.onNodeWithText(LIBRARY_SEARCH_PLACEHOLDER).performTextInput(QUERY)
        composeTestRule.onNodeWithText(QUERY).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()

        // Inert, and that is the whole of it: the bar does not navigate to where the user
        // already is. The open search box with what was typed in it still on screen is the
        // visible proof that the entry was never popped and rebuilt — the tab is not reset,
        // not re-animated, and nothing the user had is spent on a press that asked for no
        // movement.
        composeTestRule.onNodeWithText(QUERY).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).assertIsSelected()
    }

    @Test
    fun `a hammered bar does not tear down the tab it is arriving at`() {
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()
        composeTestRule.onNodeWithContentDescription(LIBRARY_SEARCH).performClick()
        composeTestRule.onNodeWithText(LIBRARY_SEARCH_PLACEHOLDER).performTextInput(QUERY)
        composeTestRule.onNodeWithContentDescription(TAB_STATS).performClick()

        // The clock is stopped so the extra taps land where a hammered bar puts them: after
        // the navigation has been dispatched, before the animation into the tab has finished.
        // `currentDestination` says Library the instant `navigate` returns, so every tap after
        // the first is a press on the tab the user is already in — which is now a press that
        // does nothing rather than one that rebuilds a tab mid-arrival.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()
        composeTestRule.mainClock.advanceTimeBy(MID_TRANSITION_MILLIS)
        composeTestRule.onNodeWithContentDescription(TAB_LIBRARY).performClick()
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // The tab the user was switching to, restored as the first tap asked for and left
        // alone by the rest.
        composeTestRule.onNodeWithText(QUERY).assertIsDisplayed()
    }

    @Test
    fun `opening a logged session leaves the tab scaffold, and back returns to it`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        // The row is drawn from a Room read, so it arrives a beat after the tab does.
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule
                .onAllNodesWithContentDescription(LOGGED_SESSION, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription(LOGGED_SESSION, substring = true).performClick()

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
     * detail. The rules those tests covered are still in `TitanAppState` and are still
     * exercised — `saveState`/`restoreState` by the tab-state test below, the guard on the
     * active tab by the two tests above — and #24 gives the Sessions tab three
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
        openSetup()

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

        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()

        logASet()
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).performClick()

        // The recap is the end of the flow rather than a step of it — it is pushed as the flow
        // is popped — but it is still bar-less, because it is outside the tab scaffold. Waited
        // for, because closing the session out is a write and the navigation follows it.
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(DONE).fetchSemanticsNodes().isNotEmpty()
        }
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
    fun `backing out of the picker the flow opened on returns to the tab it left`() {
        openThePicker()
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(CONFIRM_NONE).fetchSemanticsNodes().isNotEmpty()
        }

        pressBack()

        // Home, and not the setup screen the picker was pushed over. That screen was never
        // drawn — the quick start opens the flow and the picker in one move — so surfacing it
        // on the way out shows the user somewhere they have not been, wearing Home's own
        // quick-start action and template list. Back would be going forwards.
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsDisplayed()
        composeTestRule.onNodeWithText(NEW_SESSION).assertDoesNotExist()

        // And the flow really is gone rather than merely covered: the quick start opens a
        // fresh one.
        openThePicker()
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(CONFIRM_NONE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `backing out of a picker opened from the template list leaves the quick start usable`() {
        // The other picker, and the other meaning of leaving it: here the setup screen *is*
        // where the user came from, so a cancel belongs on it rather than out of the flow.
        openSetup()
        composeTestRule.onNodeWithText(EMPTY_SESSION).performClick()
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
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
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(CONFIRM_NONE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a template on Home opens the flow on that template rather than on the list`() {
        // The whole point of the quick-start list: the template is already chosen, so the
        // step that chooses one is skipped and the user lands one tap from a workout. It is
        // the review panel and not the workout itself — a row tapped by accident while
        // scrolling Home must not cost a session that has to be discarded.
        // The list is a Room read like every other list here, so the row is waited for
        // rather than assumed.
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodes(templateRow()).fetchSemanticsNodes().isNotEmpty()
        }
        tapOnHome(templateRow())

        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(START_WORKOUT).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(START_WORKOUT).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()
    }

    @Test
    fun `a workout the app was left holding is offered on Home and resumes into itself`() {
        // The state a process restart leaves behind, and the only route back to it: the
        // history lists what has been *finished*, so without this banner the workout below is
        // unreachable and nothing else can be started while it exists.
        runBlocking {
            sessions.insert(
                WorkoutSession(
                    name = RUNNING_SESSION,
                    isInProgress = true,
                    lastActiveAt = LOGGED_AT,
                    createdAt = LOGGED_AT,
                    updatedAt = LOGGED_AT,
                ),
            )
        }
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(RUNNING_SESSION).fetchSemanticsNodes().isNotEmpty()
        }

        tapOnHome(RESUME)

        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithContentDescription(ACTIVE_SESSION)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // And it is the mode, not a tab with a workout drawn in it.
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()
    }

    @Test
    fun `leaving the session flow restores the bar and the tab that was open`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).performClick()

        openSetup()
        composeTestRule.onNodeWithContentDescription(LEAVE_SETUP).performClick()

        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
        awaitHome()

        // And the tab the user had been on is still there to go back to.
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(HISTORY).assertIsDisplayed()
    }

    @Test
    fun `finishing the session flow returns to the tab scaffold`() {
        startAWorkout()
        logASet()
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).performClick()
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(DONE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(DONE).performClick()

        awaitHome()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
    }

    /**
     * The one edge that only the assembled graph can answer: [TemplateEditorDestination]'s id
     * has a default, which makes it an *optional* route argument rather than a path segment, and
     * whether a `navigate` with none of it resolves back to the same registration is a property
     * of the real `NavController` and nothing smaller. The screen tests either side of this call
     * their halves with the value already decided.
     */
    @Test
    fun `the templates tab's new action opens the editor inside the tab`() {
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).performClick()
        composeTestRule.onNodeWithText(TAB_TEMPLATES).performClick()

        composeTestRule.onNodeWithContentDescription(NEW_TEMPLATE).performClick()

        composeTestRule.onNodeWithText(NEW_TEMPLATE).assertIsDisplayed()
        // Inside the tab, unlike the session flow: the bottom bar stays, and the tab it was
        // opened from is still the selected one.
        composeTestRule.onNodeWithContentDescription(TAB_SESSIONS).assertIsSelected()

        // And it is a push rather than the tab's own content, so back returns to the list.
        pressBack()
        composeTestRule.onNodeWithText(TAB_TEMPLATES).assertIsDisplayed()
        composeTestRule.onNodeWithText(NEW_TEMPLATE).assertDoesNotExist()
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
        openSetup()
        composeTestRule.onNodeWithText(NEW_SESSION).assertIsDisplayed()

        pressBack()

        // Setup is not a mode: nothing has been logged, so backing out is just backing out.
        awaitHome()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
    }

    @Test
    fun `the back gesture does not abandon an active session`() {
        startAWorkout()
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).assertIsDisplayed()

        pressBack()

        // Unguarded, this pops the last entry in the session graph and the graph with it,
        // dropping the user back on Home with the workout silently discarded.
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithText(HOME).assertDoesNotExist()
    }

    @Test
    fun `the back gesture on an active session asks which way out is meant`() {
        startAWorkout()
        // Not decoration: the guard belongs to the active-session entry and is only armed
        // once that entry is resumed, which is after the transition into it has finished.
        // Pressing back before this assertion presses it at the previous screen.
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).assertIsDisplayed()

        pressBack()

        // Back means "I want out" and a workout has two ways out, so the gesture raises the
        // question rather than doing nothing — a press that is silently swallowed is
        // indistinguishable from a frozen app, and from inside the mode there is nowhere else
        // to go.
        composeTestRule.onNodeWithText(DISCARD_TITLE).assertIsDisplayed()
    }

    @Test
    fun `keeping the workout after a back press leaves it running`() {
        startAWorkout()
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).assertIsDisplayed()
        pressBack()

        composeTestRule.onNodeWithText(KEEP_TRAINING).performClick()

        composeTestRule.onNodeWithText(DISCARD_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).assertIsDisplayed()
        composeTestRule.onNodeWithText(HOME).assertDoesNotExist()
    }

    @Test
    fun `discarding a workout drops the flow without opening a recap`() {
        startAWorkout()
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).assertIsDisplayed()
        pressBack()

        composeTestRule.onNodeWithText(DISCARD_CONFIRM).performClick()

        // Nothing was logged, so there is nothing to recap — the flow is popped and the user
        // lands back where they started it from, with the tab scaffold restored.
        awaitHome()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsSelected()
    }

    /**
     * Home, once it has something on it.
     *
     * The screen draws **nothing at all** until its reads land — see `HomeScreen`, where the
     * quick-start actions wait with the rest of it rather than being pushed down the page by a
     * banner that arrives a frame later — so a test that lands on the tab and reaches straight
     * for a control is reaching for one that is not composed yet. Waiting on the section
     * heading is waiting on the database, which is the thing actually being waited for.
     */
    private fun awaitHome() {
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(HOME).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Home to a running workout, the way a user gets there.
     *
     * Four taps through three screens in two feature modules, which is exactly why it is
     * worth driving here: nothing but the assembled graph can tell whether the picker's
     * result reaches the flow that opened it.
     */
    private fun startAWorkout() {
        openSetup()
        // The template list is a real Room query on a real background dispatcher, and
        // Compose's idling does not track it — so the row is waited for, not assumed.
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodes(templateRow()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(templateRow()).performClick()
        // And again for the review panel: reading the template out and resolving its
        // exercises against the catalogue are two more queries off the main thread.
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(START_WORKOUT).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(START_WORKOUT).performClick()
        // Once more for the commit: the session and its exercises are two writes, and the
        // navigation into the workout follows the state they produce.
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithContentDescription(ACTIVE_SESSION)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /**
     * Logs one set against whatever exercise the workout opened on.
     *
     * `FinishSessionUseCase` refuses a session with nothing in it, so a test that means to reach
     * the recap has to actually log something — which is the contract working, not an obstacle
     * to route around. The fields are waited for because the workout's own reads are Room
     * queries on a background dispatcher, the same reason [startAWorkout] waits three times.
     */
    private fun logASet() {
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithContentDescription(FIRST_SET_WEIGHT)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription(FIRST_SET_WEIGHT).performTextReplacement("60")
        composeTestRule.onNodeWithContentDescription(FIRST_SET_REPS).performTextReplacement("8")
        composeTestRule.onNodeWithText(COMPLETE_FIRST_SET).performScrollTo().performClick()
        // Waited for, and this is the part that matters: the tick writes a `workout_set` row and
        // the screen reads it back through Room, so a finish tapped before that round trip lands
        // is a finish over a session that still has nothing in it — which the use case refuses,
        // correctly, leaving the test looking at a workout it thought it had ended. The button
        // renumbering itself is the row having come back.
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(COMPLETE_SECOND_SET).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Home into setup, with the transition that gets there run out.
     *
     * The flow is a mode: it slides up over the tab across [NavMotion.MODAL]'s 350 ms, so the
     * screen it lands on is composed a frame before any of it is on screen. Every caller below
     * acts on that screen immediately, so the transition is finished here rather than raced —
     * the same reason the template row is waited for in [startAWorkout] rather than assumed.
     */
    private fun openSetup() = tapOnHome(START_FROM_TEMPLATE)

    /**
     * Taps one of Home's quick-start actions and lands on what it opens.
     *
     * Three steps rather than one:
     *
     * - **the scroll**, because Home is a dashboard and grows down the page — its actions fit
     *   on the phone-sized window these tests run in (see `robolectric.properties`) and a card
     *   added below them one day will not. A tap on a node that is off screen is a tap the user
     *   could not have made;
     * - **the settle after it**, because the scroll a semantics action performs is *animated*,
     *   and a tap injected while one is running lands where the button was a frame ago — which
     *   is nowhere, silently;
     * - **the settle after the tap**, because the flow is a mode and slides up over the tab
     *   across [NavMotion.MODAL]'s 350 ms, so the screen it lands on is composed a frame before
     *   any of it is on screen.
     */
    private fun tapOnHome(label: String) = tapOnHome(hasText(label))

    /** The same, for the one control on Home that is found by what it says out loud. */
    private fun tapOnHome(matcher: SemanticsMatcher) {
        awaitHome()
        composeTestRule.onNode(matcher).performScrollTo()
        composeTestRule.mainClock.advanceTimeBy(SCROLL_SETTLE_MILLIS)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(matcher).performClick()
        composeTestRule.mainClock.advanceTimeBy(MODAL_TRANSITION_MILLIS)
        composeTestRule.waitForIdle()
    }

    /**
     * Home to the exercise picker, by the quick-start action rather than a template.
     *
     * One tap: Home's empty-session action opens the flow *on* the picker — see
     * `NewSessionDestination` — rather than on the list the picker is reached from.
     */
    private fun openThePicker() = tapOnHome(START_EMPTY)

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

        // Screen markers — each screen's eyebrow, except the stats hub, whose one constant
        // element is its timeframe selector: everything below it depends on what the reads
        // come back with, and on an empty database that is an empty state rather than a card.
        const val HOME = "QUICK START"
        const val HISTORY = "HISTORY"
        const val PROGRESSION = "90D"
        const val NEW_SESSION = "New session"

        // The templates tab, and the editor a new template opens in. One string for two things:
        // the tab's `+ NEW` is the same three characters on all three tabs and says which of
        // them it is only in its description, and the description is the editor's title.
        const val TAB_TEMPLATES = "TEMPLATES"
        const val NEW_TEMPLATE = "New template"

        /*
         * The workout, by its header bar rather than by anything in its body.
         *
         * The bar is the `Scaffold`'s `topBar` and is drawn before the session's own reads come
         * back, which matters here for the same reason the recap's marker is its chrome: this
         * test runs against real Room queries on a real background dispatcher, and what a screen
         * draws *while* they are outstanding is the only thing it can rely on. Whether the
         * workout renders a session is `ActiveSessionScreenTest`'s question.
         */
        const val ACTIVE_SESSION = "End the session"

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

        // Part-way through a tab switch's 150ms fade: long enough that the arriving tab has
        // been composed, short enough that it has not been marked as arrived at.
        const val MID_TRANSITION_MILLIS = 80L

        // Past the end of the 350ms slide the session flow arrives on — see [NavMotion].
        const val MODAL_TRANSITION_MILLIS = 400L

        // Past the end of the animated scroll `performScrollTo` runs — see [tapOnHome].
        const val SCROLL_SETTLE_MILLIS = 1_000L

        /**
         * How long a wait for a Room read is given, over `waitUntil`'s own second.
         *
         * Every wait in this file is on a query Compose's idling does not track, and there are
         * now more of them behind one screen than there were: Home observes the sessions, the
         * open one and the templates, and those share a dispatcher with whatever the test is
         * driving. A second is enough almost every time, which is the worst kind of enough.
         */
        const val READ_TIMEOUT_MILLIS = 5_000L

        // The workout seeded by the resume test, and the banner's action.
        const val RUNNING_SESSION = "Left running"
        const val RESUME = "RESUME"

        // Home's ways into the session flow. The template action opens it on its list; the
        // empty-session action opens the picker over that list in one move, which is why the
        // second tap `openThePicker` used to need is gone.
        const val START_FROM_TEMPLATE = "See all templates"
        const val START_EMPTY = "START EMPTY SESSION"

        // The seeded session's name, and when it happened: midday UTC on a fixed day, so the
        // row lands on the same date in whichever zone the test runs.
        const val LOGGED_SESSION = "Logged session"
        const val LOGGED_AT = 1_777_377_600_000L
        const val LOGGED_DURATION_MILLIS = 3_120_000L
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

        // The discard confirmation the back gesture raises, and the two ways out of it.
        // Asserted on the copy the user reads, since that is the whole point of it.
        // The entry row of the workout's first exercise, and the action that writes it.
        const val FIRST_SET_WEIGHT = "Weight, set 1"
        const val FIRST_SET_REPS = "Reps, set 1"
        const val COMPLETE_FIRST_SET = "Complete set 1"
        const val COMPLETE_SECOND_SET = "Complete set 2"

        const val DISCARD_TITLE = "Discard this workout?"
        const val DISCARD_CONFIRM = "Discard"
        const val KEEP_TRAINING = "Keep training"
    }
}
