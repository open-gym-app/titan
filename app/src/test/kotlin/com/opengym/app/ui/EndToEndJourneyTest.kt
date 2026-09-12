package com.opengym.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
import com.opengym.app.HiltTestActivity
import com.opengym.app.navigation.NavMotion
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.ui.theme.OpenGymTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The four journeys a user actually takes, each spanning several screens in several modules.
 *
 * ### What this suite is for, and what it deliberately leaves to others
 *
 * Every screen here already has its own tests, and `OpenGymAppNavigationTest` already covers
 * the shell: which destination a tap reaches, whether the bar is drawn, what the back gesture
 * intercepts. Neither can answer the question this file exists for — **does the state a user
 * builds on one screen survive the trip to the next one, through the real graph, the real
 * ViewModels and the real database?**
 *
 * So nothing here re-asserts rendering that a per-screen test already owns. A journey ends in
 * an assertion about *continuity*: the sets logged in the workout are the sets the recap
 * counts, the workout left running is the workout resumed into, the template saved off a
 * finished session is the template a new session starts from, the exercise picked in one
 * module is the exercise the flow in another module receives. Duplicating the per-screen
 * assertions here would double the maintenance for no coverage — see #29.
 *
 * ### Why real repositories rather than fakes
 *
 * #29 was written before `:app`'s test harness existed and asked for fake repositories. What
 * landed instead — and what `OpenGymAppNavigationTest` established — is the whole graph over
 * the real Hilt bindings, and these journeys keep it, deliberately:
 *
 * - Three of the four are *about* persistence. "The logged set is still there after the
 *   ViewModel layer is rebuilt" and "the template round-trips" are claims about Room and
 *   DataStore; asserted against fakes they would restate the fakes.
 * - The finish path runs `SessionCompletionPipeline`, whose whole contract is what it writes.
 *   A fake repository under it tests the test.
 * - Faking the twenty-odd repositories behind this graph is a second implementation of the
 *   data layer to keep in step with the first, which is the maintenance cost the paragraph
 *   above declines to pay for assertions.
 *
 * Robolectric gives each test its own sandboxed database, so "no real database is touched" is
 * still true in the sense that matters: nothing here reads or writes a device's data, and the
 * tests do not share state.
 *
 * ### The catalogue is real, and that shapes journey four
 *
 * The bundled seed **does** load here — the picker draws 894 rows — so an exercise is not
 * found by name in the unfiltered list: `LazyColumn` has composed the first screenful and
 * nothing else, and waiting for a row further down is waiting for recycling rather than for
 * the app. The two exercises these journeys add are therefore named so that nothing in the
 * catalogue collides with them, and journey four searches before it taps.
 *
 * ### Why everything is waited for
 *
 * Every screen in these journeys reads from Room on a background dispatcher, and Compose's
 * idling does not track those. A tap on a node that has not arrived yet is a tap on nothing,
 * silently — so every step waits for what it is about to act on. There is no `Thread.sleep`
 * here and there must not be: [READ_TIMEOUT_MILLIS] bounds a `waitUntil` on a condition, which
 * fails loudly and fast rather than passing slowly.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class EndToEndJourneyTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @Inject
    lateinit var exercises: ExerciseRepository

    @Inject
    lateinit var templates: TemplateRepository

    @Inject
    lateinit var sessions: SessionRepository

    /**
     * Whether the app is composed at all.
     *
     * Flipping it off and on again is how [restartTheViewModelLayer] simulates the process
     * coming back: the whole tree is disposed, taking the `NavController` and every
     * nav-entry-scoped ViewModel with it, and the tree that replaces it builds its state from
     * what survived in Room and DataStore rather than from anything held in memory.
     */
    private var appIsComposed by mutableStateOf(true)

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking {
            journeyPressId = exercises.insert(Exercise(name = JOURNEY_PRESS))
            exercises.insert(Exercise(name = JOURNEY_ROW))
        }
        composeTestRule.setContent {
            /*
             * Two things are turned off around the app, and only two.
             *
             * `reducedMotion = true`, because the recap's level-up ceremony — the sweep, the
             * wreath, the confetti — animates for as long as it is on screen, and under
             * Robolectric *every* node query waits for the screen to report itself idle
             * before it reads. Left on, the first read after the recap renders blocks until
             * Espresso gives up sixty seconds later. It is not a hang in the app; it is a
             * celebration doing its job. This is the switch the design system already
             * provides for exactly that, passed the way `ReducedMotion.kt` says to pass it —
             * as the theme's own parameter, not as an outer `CompositionLocalProvider`, which
             * the theme would shadow.
             *
             * And an unfocused `WindowInfo`, because every text field these journeys drive
             * takes real keyboard focus, and a focused field's caret blinks for as long as its
             * window stays focused. Left alone that is one more source of the same "idle never
             * arrives" failure `reducedMotion` exists for — a real, harmless, endless animation
             * that Robolectric's idling has no exemption for. It does not reach `TemplateNameDialog`
             * or `SessionNameDialog`: both open their own platform window, which shadows this
             * provision with its own real, focused `WindowInfo` — see #187, which turned out to
             * be a different bug entirely (`OpenGymDialog`'s doc has the story) rather than the
             * caret this override was written for.
             *
             * Nothing under test reads either flag. Whether the ceremony plays is
             * `SessionSummaryScreenTest`'s subject; these journeys are about what the screens
             * agree on, and they get the still rendering of it.
             */
            val window = LocalWindowInfo.current
            val unfocused = remember(window) {
                object : WindowInfo by window {
                    override val isWindowFocused: Boolean get() = false
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides unfocused) {
                OpenGymTheme(reducedMotion = true) {
                    if (appIsComposed) {
                        OpenGymApp(startsOnOnboarding = false)
                    }
                }
            }
        }
    }

    /**
     * Home → template → workout → two logged sets → recap → Home.
     *
     * The assertion that matters is the last pair: the recap names the exercise the workout
     * was built from and counts the sets that were actually logged, and the finished session
     * is in the history afterwards. Both cross a module boundary the per-screen tests cannot
     * see across — the recap is `:feature:session` reading rows the workout wrote, and the
     * history is `:feature:history` reading the same ones.
     */
    @Test
    fun `a workout logged from a template is the workout the recap reports`() {
        seedTemplate()

        startTheWorkoutFromTheTemplate()
        logSet(number = 1, weight = "60", reps = "8")
        logSet(number = 2, weight = "62.5", reps = "6")

        finishTheWorkout()

        // The recap, over the rows the workout just wrote — and read off its stat tiles
        // rather than out of the exercise list below them. The tiles are the recap's own
        // arithmetic over those rows, they are the part of it a user reads first, and they
        // are on screen without scrolling a list whose items are lazily composed.
        //
        // Two sets, because two were logged three screens ago. One exercise, because the
        // template named one. Neither number exists anywhere the workout could have handed
        // it over directly: both are counted here, from the database, by a different module.
        awaitContentDescription(TWO_SETS_LOGGED)
        // Scrolled to, because the recap is taller than the window and the fourth tile of the
        // grid sits below the fold on a phone. A tile that has to be scrolled to is still a
        // tile the user is shown; one that is merely in the semantics tree might not be.
        composeTestRule.onNodeWithContentDescription(ONE_EXERCISE_DONE)
            .performScrollTo()
            .assertIsDisplayed()

        // And the session carries the name the template gave it, four screens back.
        composeTestRule.onNodeWithText(TEMPLATE).assertIsDisplayed()

        leaveTheRecap()

        // The journey's other product, and the one the screen cannot show: the two sets are
        // attached to the one finished session. Read back through the repository rather than
        // off the history list, because what that list renders is `SessionListScreenTest`'s
        // subject and re-asserting it here would be the duplication this file avoids.
        runBlocking {
            val finished = sessions.getAll().first().single { !it.isInProgress }
            assertEquals(2, sessions.getSetsForSession(finished.id).first().count { it.isCompleted })
        }
    }

    /**
     * A workout survives the ViewModel layer being torn down and rebuilt.
     *
     * The closest thing to a process restart this harness can stage, and the property under
     * test is exactly the one a restart threatens: after the rebuild there is no in-memory
     * state left, so Home offering the workout and the workout still holding its set can only
     * have come from what was persisted while it ran.
     */
    @Test
    fun `a workout survives the ViewModel layer being rebuilt and resumes with its set`() {
        seedTemplate()

        startTheWorkoutFromTheTemplate()
        logSet(number = 1, weight = "60", reps = "8")

        restartTheViewModelLayer()

        // Home is where a restart lands, and the banner is the only route back to a workout
        // that was never finished — the history lists what has been finished.
        awaitHome()
        awaitText(RESUME)
        tapOnHome(hasText(RESUME))
        awaitActiveSession()

        // The set is still there: its fields read back what was typed before the rebuild, and
        // the next row is numbered two rather than one.
        composeTestRule.onNodeWithContentDescription(FIRST_SET_WEIGHT).assertIsDisplayed()
        awaitText(COMPLETE_SECOND_SET)
    }

    /**
     * A finished session becomes a template, and that template starts the next session.
     *
     * The round trip crosses `:feature:session` (which records what was performed) and
     * `:feature:history` (which lists the template) before landing back in the flow, and the
     * claim is that the exercise makes the whole trip — the template a workout produced is a
     * template that produces the same workout.
     */
    @Test
    fun `a session saved as a template starts the next session with the same exercise`() {
        seedTemplate()

        startTheWorkoutFromTheTemplate()
        logSet(number = 1, weight = "60", reps = "8")
        finishTheWorkout()

        // The recap offers the action, and only once it knows what was performed — the bar
        // draws DONE as soon as the screen exists, but "Save as template" is three Room reads
        // behind it. No `performScrollTo`: the bar is pinned and has no scrollable parent, so
        // asking to scroll to one fails rather than no-opping.
        awaitText(SAVE_AS_TEMPLATE)
        composeTestRule.onNodeWithText(SAVE_AS_TEMPLATE).assertIsDisplayed()

        // Through the dialog the button opens, not around it — see #187, now closed: what
        // hung here was never this journey's own state, it was `AlertDialog` and a text field
        // never settling on a width at the size this suite drives the shell at. `OpenGymDialog`
        // is the fix; see its own doc. The name is replaced rather than kept, so the row this
        // produces (`SAVED_TEMPLATE`) is distinguishable from the seeded one (`TEMPLATE`) in
        // the list four screens from here.
        composeTestRule.onNodeWithText(SAVE_AS_TEMPLATE).performClick()
        awaitContentDescription(TEMPLATE_NAME_FIELD)
        composeTestRule.onNodeWithContentDescription(TEMPLATE_NAME_FIELD)
            .performTextReplacement(SAVED_TEMPLATE)
        composeTestRule.onNodeWithText(SAVE_TEMPLATE_CONFIRM).performClick()
        awaitText(SAVED_AS_TEMPLATE_MESSAGE)

        leaveTheRecap()

        // Back into the flow, on the template the session produced rather than the seeded one.
        openTheTemplateList()
        awaitNode(templateRow(SAVED_TEMPLATE))
        composeTestRule.onNode(templateRow(SAVED_TEMPLATE)).performClick()
        awaitText(START_WORKOUT)

        // The exercise survived the round trip: session → template → session.
        composeTestRule.onNodeWithText(JOURNEY_PRESS).assertIsDisplayed()

        runBlocking {
            val saved = templates.getAll().first().single { it.name == SAVED_TEMPLATE }
            val exerciseIds = templates.getExercises(saved.id).first().map { it.exerciseId }
            assertEquals(listOf(journeyPressId), exerciseIds)
        }
    }

    /**
     * Picker → an exercise's detail → back into the picker holding it.
     *
     * The one edge in the app where a screen in `:feature:history` hands a value to a flow in
     * `:feature:session` without either module naming the other: the detail reports the id,
     * `:app` writes it onto the picker's back-stack entry, and the picker reads it back. Only
     * the assembled graph can say whether that hand-off arrives — a test over either screen
     * alone passes with the wiring cut.
     */
    @Test
    fun `an exercise added from its detail screen reaches the picker that opened it`() {
        openThePicker()
        awaitText(CONFIRM_NONE)

        // Searched down to the two seeded rows before anything is tapped. The picker draws
        // the whole bundled catalogue — 894 rows here — so a row picked by name out of the
        // unfiltered list is a row `LazyColumn` has probably not composed, and a test that
        // waits for one is waiting for recycling rather than for the app.
        composeTestRule.onNodeWithContentDescription(LIBRARY_SEARCH).performClick()
        composeTestRule.onNodeWithText(LIBRARY_SEARCH_PLACEHOLDER).performTextInput(QUERY)
        awaitContentDescription(detailsOf(JOURNEY_PRESS))

        composeTestRule.onNodeWithContentDescription(detailsOf(JOURNEY_PRESS)).performClick()
        awaitText(ADD_TO_SESSION)
        composeTestRule.onNodeWithText(ADD_TO_SESSION).performClick()

        // Back in the picker, and holding what the detail screen sent it: the confirm bar
        // counts one where it said "Select exercises" a moment ago.
        awaitText(CONFIRM_ONE)
        composeTestRule.onNodeWithText(CONFIRM_NONE).assertDoesNotExist()
    }

    // ── The journeys' shared steps ──────────────────────────────────────────────────────

    /**
     * The template the first three journeys start from.
     *
     * Seeded per test rather than in [setUp], because the fourth journey never opens the
     * template list and a row it does not use is a row the picker has to draw past.
     */
    private fun seedTemplate() {
        runBlocking {
            templates.insert(
                template = WorkoutTemplate(name = TEMPLATE),
                exercises = listOf(TemplateExerciseDraft(exerciseId = journeyPressId)),
            )
        }
    }

    /** Home → template list → the seeded template → a running workout. */
    private fun startTheWorkoutFromTheTemplate() {
        openTheTemplateList()
        awaitNode(templateRow(TEMPLATE))
        composeTestRule.onNode(templateRow(TEMPLATE)).performClick()
        awaitText(START_WORKOUT)
        composeTestRule.onNodeWithText(START_WORKOUT).performClick()
        awaitActiveSession()
    }

    /**
     * Logs one set and waits for it to come back from the database.
     *
     * The wait is the load-bearing part: the tick writes a `workout_set` row and the screen
     * re-reads it, and a step taken before that round trip lands is a step over a session that
     * still has nothing in it. The next row appearing is the previous one having arrived.
     */
    private fun logSet(
        number: Int,
        weight: String,
        reps: String,
    ) {
        awaitContentDescription(setWeightLabel(number))
        composeTestRule.onNodeWithContentDescription(setWeightLabel(number))
            .performTextReplacement(weight)
        composeTestRule.onNodeWithContentDescription(setRepsLabel(number))
            .performTextReplacement(reps)
        composeTestRule.onNodeWithText(completeSetLabel(number)).performScrollTo().performClick()
        awaitText(completeSetLabel(number + 1))
    }

    /**
     * The workout's finish action, and the recap it lands on.
     *
     * DONE is waited for rather than the recap's body: it is the bar, drawn as soon as the
     * screen exists, where everything below it is three Room reads away. What each journey
     * then reads off the recap, it waits for itself.
     *
     * Readable at all only because the app is composed with `reducedMotion = true` — see the
     * comment in [setUp]. This is the screen that made that necessary.
     */
    private fun finishTheWorkout() {
        composeTestRule.onNodeWithContentDescription(ACTIVE_SESSION).performClick()
        awaitText(DONE)
    }

    /** Leaves the recap for Home. */
    private fun leaveTheRecap() {
        composeTestRule.onNodeWithText(DONE).performClick()
        awaitHome()
    }

    /**
     * Disposes the whole app and composes it again.
     *
     * Two frames rather than one flip: the tree has to actually leave the composition — taking
     * the `NavController` and the ViewModels the nav entries own with it — before the
     * replacement is built, or the "restart" is a recomposition and proves nothing.
     */
    private fun restartTheViewModelLayer() {
        appIsComposed = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(TAB_HOME).assertDoesNotExist()

        appIsComposed = true
        composeTestRule.waitForIdle()
    }

    private fun openTheTemplateList() = tapOnHome(hasText(START_FROM_TEMPLATE))

    private fun openThePicker() = tapOnHome(hasText(START_EMPTY))

    /**
     * Taps one of Home's quick-start actions and lands on what it opens.
     *
     * Scrolled to first because Home grows down the page, settled after because the scroll a
     * semantics action performs is animated, and settled again after the tap because the
     * session flow slides up over the tab across [NavMotion.MODAL]'s 350 ms. A tap injected
     * into any of those three windows lands where the button was a frame ago.
     */
    private fun tapOnHome(matcher: SemanticsMatcher) {
        awaitHome()
        composeTestRule.onNode(matcher).performScrollTo()
        composeTestRule.mainClock.advanceTimeBy(SCROLL_SETTLE_MILLIS)
        composeTestRule.waitForIdle()

        composeTestRule.onNode(matcher).performClick()
        composeTestRule.mainClock.advanceTimeBy(MODAL_TRANSITION_MILLIS)
        composeTestRule.waitForIdle()
    }

    /** A template's row, by the sentence it speaks; the rest of it is derived and not ours. */
    private fun templateRow(name: String) = hasContentDescription(name, substring = true)

    private fun detailsOf(exercise: String) = DETAILS_OF.format(exercise)

    private fun setWeightLabel(number: Int) = SET_WEIGHT.format(number)

    private fun setRepsLabel(number: Int) = SET_REPS.format(number)

    private fun completeSetLabel(number: Int) = COMPLETE_SET.format(number)

    private fun awaitHome() = awaitText(HOME)

    private fun awaitActiveSession() = awaitContentDescription(ACTIVE_SESSION)

    private fun awaitText(text: String) =
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitContentDescription(description: String) =
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

    private fun awaitNode(matcher: SemanticsMatcher) =
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private var journeyPressId: Long = 0

    private companion object {
        // Screen markers, each the one thing its screen draws before any read comes back.
        const val HOME = "QUICK START"
        const val TAB_HOME = "Home"
        const val TAB_SESSIONS = "Sessions"
        const val ACTIVE_SESSION = "End the session"
        const val DONE = "Done"

        // Home's two ways into the flow: onto the template list, and straight onto the picker.
        const val START_FROM_TEMPLATE = "Start from template"
        const val START_EMPTY = "START EMPTY SESSION"
        const val RESUME = "RESUME"
        const val START_WORKOUT = "Start workout"

        /*
         * The catalogue these journeys add, and the template built over the first of them.
         *
         * Named so they cannot collide with the 873 bundled exercises, which *do* load in this
         * harness: "Bench press" would match several seeded rows and make `Details of …` an
         * ambiguous matcher, where "Journey press" is exactly one row and [QUERY] finds it and
         * nothing else.
         */
        const val JOURNEY_PRESS = "Journey press"
        const val JOURNEY_ROW = "Journey row"
        const val TEMPLATE = "Push day"

        // The template the recap makes, and the dialog that names it.
        const val SAVE_AS_TEMPLATE = "Save as template"
        const val SAVED_TEMPLATE = "Bench day"
        const val TEMPLATE_NAME_FIELD = "Template name"
        const val SAVE_TEMPLATE_CONFIRM = "Save"
        const val SAVED_AS_TEMPLATE_MESSAGE = "Saved as a template"

        // The set entry rows, by the description a screen reader reads.
        const val SET_WEIGHT = "Weight, set %d"
        const val SET_REPS = "Reps, set %d"
        const val COMPLETE_SET = "Complete set %d"
        const val COMPLETE_SECOND_SET = "Complete set 2"
        const val FIRST_SET_WEIGHT = "Weight, set 1"

        // The recap's stat tiles, by the sentence a screen reader reads off them.
        const val TWO_SETS_LOGGED = "Sets, 2 total"
        const val ONE_EXERCISE_DONE = "Exercises, 1 done"

        // The picker: its search, and its confirm bar before and after one exercise is added.
        const val LIBRARY_SEARCH = "Search"
        const val LIBRARY_SEARCH_PLACEHOLDER = "Search exercises…"
        const val QUERY = "journey"
        const val DETAILS_OF = "Details of %s"
        const val ADD_TO_SESSION = "Add to session"
        const val CONFIRM_NONE = "Select exercises"
        const val CONFIRM_ONE = "Add 1 exercise"

        // Past the end of the animated scroll `performScrollTo` runs.
        const val SCROLL_SETTLE_MILLIS = 1_000L

        // Past the end of the 350ms slide the session flow arrives on — see [NavMotion].
        const val MODAL_TRANSITION_MILLIS = 400L

        /**
         * How long a wait for a Room read is given, over `waitUntil`'s own second.
         *
         * These journeys chain more reads behind one tap than any single-screen test does —
         * the recap alone combines three — and they share a dispatcher with whatever the test
         * is driving. A second is enough almost every time, which is the worst kind of enough.
         */
        const val READ_TIMEOUT_MILLIS = 5_000L
    }
}
