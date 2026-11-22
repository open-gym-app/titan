package com.titan.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titan.app.HiltTestActivity
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.ui.theme.TitanTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Where the app opens on a fresh install, and what it leaves behind on the way out.
 *
 * The shell's *other* navigation test runs with `startsOnOnboarding = false`, which is every
 * launch after the first. This one is the first launch: it drives the real flow against the
 * real preferences store and asserts the two things that cannot be tested inside
 * `:feature:onboarding` — that finishing lands on Home, and that the back gesture from there
 * does not put five just-answered steps back on the screen.
 *
 * Nothing is faked. The store behind this is the real `UserPreferencesRepository` over a real
 * (empty) preferences file, so "the answers were persisted" is asserted against what the app
 * would actually read on its next launch.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class OnboardingRoutingTest {

    // Order matters: the Hilt rule has to have built the component before the activity the
    // compose rule launches asks it for one.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    // The Android flavour of the rule rather than `createComposeRule()`, for the activity: the
    // back gesture is dispatched through its `OnBackPressedDispatcher`, which is where
    // onboarding's own step-by-step handler registers.
    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var preferences: UserPreferencesRepository

    @Before
    fun setUp() {
        hilt.inject()
        composeTestRule.setContent {
            TitanTheme {
                // What `MainViewModel` computes on a device whose `onboardingCompleted` is
                // false. The latch itself is `MainViewModelTest`'s subject.
                TitanApp(startsOnOnboarding = true)
            }
        }
    }

    @Test
    fun `a first launch opens on onboarding, with neither bar`() {
        composeTestRule.onNodeWithText(STEP_ONE).assertIsDisplayed()

        // Onboarding is outside the tab scaffold, so the shell draws no chrome over it. A tab
        // strip on the first screen of a first run is an invitation to leave before answering.
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(SETTINGS_ACTION).assertDoesNotExist()
    }

    @Test
    fun `finishing lands on Home and takes onboarding off the back stack`() {
        answerEveryStep()
        awaitHome()

        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertIsDisplayed()

        pressBack()

        // The acceptance criterion, and the reason the move out is a `popUpTo` rather than a
        // push: back from Home leaves the app. It does not return to a flow the user has just
        // finished, and it does not re-ask a question they have already answered.
        composeTestRule.onNodeWithText(STEP_ONE).assertDoesNotExist()
        composeTestRule.onNodeWithText(HOME).assertIsDisplayed()
    }

    @Test
    fun `the answers are on disk before Home is drawn`() {
        answerEveryStep()

        // Read once Home is up, because Home being up is the deadline this asserts against —
        // and because it is the only event that means the write has had its chance. The
        // completion write runs on the store's own dispatcher, so `waitForIdle` returning
        // says the main looper and the recomposer are quiet, not that anything reached disk;
        // reading here without waiting passes or fails on how busy the machine is.
        //
        // Waiting does not weaken the assertion. `finish` flips `isFinished` only after
        // `setOnboardingCompleted` returns, and the move to Home follows `isFinished`, so a
        // version that navigated first would draw Home with the flag still false and be
        // caught right here. What the wait removes is the case where this test fails while
        // the app is behaving correctly.
        awaitHome()

        val stored = runBlocking { preferences.observe().first() }
        assertEquals(TrainingGoal.HYPERTROPHY, stored.trainingGoal)
        assertEquals(FitnessLevel.INTERMEDIATE, stored.fitnessLevel)
        assertEquals(UnitSystem.IMPERIAL, stored.unitSystem)
        // The flag is what stops the *next* launch opening here, and it is written last.
        assertTrue(stored.onboardingCompleted)
    }

    @Test
    fun `back inside the flow walks the steps rather than leaving it`() {
        composeTestRule.onNodeWithText(CONTINUE).performClick()
        awaitDisplayed(GOAL_TITLE)

        pressBack()

        // Still in onboarding, one question earlier — and nothing has been recorded, so a
        // launch interrupted here comes back to the flow rather than to Home.
        composeTestRule.onNodeWithText(STEP_ONE).assertIsDisplayed()
        assertFalse(runBlocking { preferences.observe().first() }.onboardingCompleted)
    }

    /** Walk the five steps, choosing a non-default answer on each of the three questions. */
    private fun answerEveryStep() {
        composeTestRule.onNodeWithText(CONTINUE).performClick()

        composeTestRule.onNodeWithText(HYPERTROPHY).performClick()
        composeTestRule.onNodeWithText(CONTINUE).performClick()

        composeTestRule.onNodeWithText(INTERMEDIATE).performClick()
        composeTestRule.onNodeWithText(CONTINUE).performClick()

        composeTestRule.onNodeWithText(IMPERIAL).performClick()
        composeTestRule.onNodeWithText(CONTINUE).performClick()

        composeTestRule.onNodeWithText(START).performClick()
        composeTestRule.waitForIdle()
    }

    /**
     * Wait for Home to have drawn.
     *
     * The screen holds everything back until its reads land, so arriving on it is not the same
     * as it being on the screen — and the Room queries behind it are not something Compose's
     * idling knows to wait for. `TitanAppNavigationTest` waits the same way for the same
     * reason.
     */
    private fun awaitHome() {
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(HOME).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Wait for [text] to be on the screen, not merely in the tree.
     *
     * The flow's state is the step combined with the preferences store, so the first step change
     * reaches the screen only once the store's first read has — a DataStore read on its own
     * dispatcher, which Compose's idling does not track and which a cold JVM on a loaded CI runner
     * can finish after the click. The step then slides in after `waitForIdle` has already
     * returned, and an assertion made at that instant finds the incoming step composed but not
     * yet displayed.
     */
    private fun awaitDisplayed(text: String) {
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                composeTestRule.onNodeWithText(text).isDisplayed()
        }
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
        // The step counter, as the eyebrow draws it.
        const val STEP_ONE = "STEP 1 OF 5"
        const val CONTINUE = "Continue"
        const val START = "Start training"

        const val GOAL_TITLE = "What are you training for?"
        const val HYPERTROPHY = "Hypertrophy"
        const val INTERMEDIATE = "Intermediate"
        const val IMPERIAL = "Imperial"

        // Home's eyebrow, and the chrome that only exists inside the tab scaffold.
        const val HOME = "QUICK START"
        const val TAB_HOME = "Home"
        const val SETTINGS_ACTION = "Settings"

        /**
         * How long a wait for Home is given, over `waitUntil`'s own second — and, being a
         * bound on a hang, the same bound for [awaitDisplayed]'s wait on the store's first read.
         *
         * A bound on a hang, not a budget for a draw. `setContent` puts *onboarding* up
         * here, so Home is only ever reached from inside a test, and it arrives cold:
         * `:feature:home` composing for the first time in the JVM, Room opening the
         * database, and the reads behind the screen — none of which Compose's idling
         * tracks, all of which land inside this one wait. Whichever test reaches Home
         * first therefore pays what the ones after it do not: 1.9s against 0.3s on an idle
         * machine, and 5.1s against 0.9s with the suite running the way CI runs it, four
         * `testDebugUnitTest` tasks at a time. Five seconds was that cold cost rather than
         * a bound on it, so a CI runner — slower again than a developer's machine under
         * the same load — failed this test on every single run.
         *
         * `TitanAppNavigationTest` keeps its own five: it starts on Home, so it pays the
         * same cost inside `setContent` and never inside a wait.
         */
        const val READ_TIMEOUT_MILLIS = 30_000L
    }
}
