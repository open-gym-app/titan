package com.titan.app.ui

import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import com.titan.app.HiltTestActivity
import com.titan.app.navigation.openDeepLink
import com.titan.app.navigation.rememberTitanAppState
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.navigation.DeepLinks
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.ui.theme.TitanTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The app's own address space, end to end: a URI in, a screen out.
 *
 * ### Why this is a test and not a manual check
 *
 * A deep link is agreed on by three things that cannot see each other — `DeepLinks` in the
 * domain, an intent filter in the manifest, and a `navDeepLink` in the feature that owns the
 * screen. When they disagree, nothing throws: the tap opens the app at whatever the user was
 * last looking at, or opens nothing at all. There is no stack trace, no log line and no failing
 * build. Both halves are checked here — that the *system* would route the URI to this app, and
 * that the *graph* then lands on the right screen with the tabs still under it.
 *
 * ### Every link goes through `openDeepLink`, and so does every test here
 *
 * The two ways a link arrives — on the activity's launch intent from cold, and at `onNewIntent`
 * with the app already running — are two ways into one funnel; see `TitanNavHost`, which
 * takes the link off the intent in both cases and opens it the same way. These drive that
 * funnel directly.
 *
 * The launch half is deliberately **not** driven by setting the activity's intent and composing
 * over it. It works, but it leaves this JVM in a state the next Robolectric test in it hangs
 * on — the flakiness this project has recorded elsewhere between Compose and Robolectric in one
 * process — and a suite that does not terminate is worth less than one that covers the shared
 * funnel. `adb shell am start -a android.intent.action.VIEW -d titan://session/new` is the
 * check for that half, and #39 records it as one.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DeepLinkNavigationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    /** The workout the active-session link points at. It has to be a real row to open. */
    @Inject
    lateinit var sessions: SessionRepository

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        hilt.inject()
    }

    /**
     * A workout in progress, for the two tests that need one.
     *
     * Seeded per test rather than in [setUp], because a session in progress makes Home draw its
     * resume banner — and that banner counts. A screen with a running clock on it is one the
     * compose rule's own teardown waits on forever, so every test that does not need the
     * workout runs without it.
     */
    private fun seedSession(): Long =
        runBlocking {
            sessions.insert(
                WorkoutSession(
                    name = SESSION_NAME,
                    isInProgress = true,
                    createdAt = STARTED_AT,
                    lastActiveAt = STARTED_AT,
                    updatedAt = STARTED_AT,
                ),
            )
        }

    // --- the system's half --------------------------------------------------

    @Test
    fun `every link this app posts resolves to this app`() {
        launch()

        // The manifest filter matches on the scheme and the host rather than on each path, so
        // what this pins is that a new *host* has been added to it. That is the one change a
        // new deep link needs, and the one nothing else would catch.
        listOf(
            DeepLinks.NEW_SESSION,
            DeepLinks.activeSession(SOME_SESSION_ID),
            DeepLinks.QUEST_LOG,
        ).forEach { link ->
            val activities = composeTestRule.activity.packageManager
                .queryIntentActivities(viewIntent(link), 0)

            assertTrue("$link resolves to nothing in this app", activities.isNotEmpty())
        }
    }

    // --- from cold ----------------------------------------------------------

    @Test
    fun `the reminder's link opens the setup flow`() {
        launch()

        openWhileRunning(DeepLinks.NEW_SESSION)

        assertOnScreen(hasText(NEW_SESSION_MARKER))
    }

    @Test
    fun `the rest timer's link opens the workout it names`() {
        launch()

        openWhileRunning(DeepLinks.activeSession(seedSession()))

        assertOnScreen(hasContentDescription(ACTIVE_SESSION_MARKER))
    }

    @Test
    fun `a link leaves a back stack that reaches Home`() {
        // The acceptance criterion, and the reason `openDeepLink` exists at all:
        // `NavController.handleDeepLink` pops the graph *inclusive* and leaves the target alone
        // on the stack, so the first back press would walk out of the app.
        launch()
        openWhileRunning(DeepLinks.NEW_SESSION)

        pressBack()

        assertOnScreen(hasText(HOME_MARKER))
    }

    @Test
    fun `an ordinary launch opens on Home`() {
        // The link is read off the activity's intent, so a launch that carries none — the
        // launcher icon, a return from recents — must be untouched by any of the above.
        launch()

        assertOnScreen(hasText(HOME_MARKER))
    }

    // --- while the app is running -------------------------------------------

    @Test
    fun `a link that arrives while the app is running is handled too`() {
        // MainActivity is `singleTop`, so a notification tapped with the app on screen is
        // delivered to `onNewIntent` and nothing about the composition changes.
        launch()

        openWhileRunning(DeepLinks.NEW_SESSION)

        assertOnScreen(hasText(NEW_SESSION_MARKER))
    }

    @Test
    fun `and leaves the same stack under it`() {
        launch()
        openWhileRunning(DeepLinks.NEW_SESSION)

        pressBack()

        assertOnScreen(hasText(HOME_MARKER))
    }

    @Test
    fun `a second link replaces the first rather than stacking on it`() {
        // The progression link tapped after the reminder's. Back is still one press from Home
        // rather than one press per notification the user has got through.
        launch()
        openWhileRunning(DeepLinks.NEW_SESSION)

        openWhileRunning(DeepLinks.NEW_SESSION)

        pressBack()
        assertOnScreen(hasText(HOME_MARKER))
    }

    @Test
    fun `a link into a host this build does not know changes nothing`() {
        // One left in the notification shade across a downgrade, or a hand-typed `adb`
        // command. `navigate` throws for an unmatched URI, and nothing is worth crashing the
        // app the user has just opened.
        launch()

        openWhileRunning("${DeepLinks.SCHEME}://nowhere/at-all")

        assertOnScreen(hasText(HOME_MARKER))
    }

    @Test
    fun `a link under a known host that names no screen changes nothing either`() {
        // Unlike the case above, the manifest's filter admits this one, so any app or web page
        // can deliver it to the running activity.
        launch()

        openWhileRunning("${DeepLinks.SCHEME}://${DeepLinks.SESSION_HOST}/nonsense/42")

        assertOnScreen(hasText(HOME_MARKER))
    }

    // --- helpers ------------------------------------------------------------

    /** Starts the shell on Home, the way an ordinary launch does. */
    private fun launch() {
        composeTestRule.setContent {
            // The app state is hoisted so the test holds the same `NavController` the shell
            // does — `TitanApp` builds one itself otherwise, and there would be nothing to
            // ask about the back stack.
            val appState = rememberTitanAppState()
            navController = appState.navController
            // Reduced motion, which is what makes these tests terminate rather than a
            // stylistic choice: the quest log's streak card draws `StreakFlame`, the one
            // perpetual animation in the app, and a composition running one never goes idle.
            // The composition local is the seam `:core:ui`'s animated components already
            // honour — see `LocalReducedMotion`.
            TitanTheme(reducedMotion = true) {
                TitanApp(startsOnOnboarding = false, appState = appState)
            }
        }
    }

    /** The `onNewIntent` path, through the function its listener calls. */
    private fun openWhileRunning(link: String) {
        composeTestRule.runOnUiThread { navController.openDeepLink(link.toUri()) }
    }

    /** One step back, the way the system delivers it. */
    private fun pressBack() {
        composeTestRule.runOnUiThread { navController.popBackStack() }
    }

    /**
     * Waits for [matcher] to appear, then asserts it is on screen.
     *
     * `waitUntil` rather than `waitForIdle`, which is what every wait in
     * `TitanAppNavigationTest` is and for a reason that bites harder here: these tests seed a
     * workout **in progress**, so Home draws its resume banner and the workout draws its own
     * elapsed time — both of which tick once a second, forever. A screen with a running clock
     * on it is never idle, and `waitForIdle` on one does not return.
     */
    private fun assertOnScreen(matcher: SemanticsMatcher) {
        composeTestRule.waitUntil(READ_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(matcher).assertIsDisplayed()
    }

    private fun viewIntent(link: String): Intent =
        Intent(Intent.ACTION_VIEW, link.toUri()).setPackage(composeTestRule.activity.packageName)

    private companion object {
        const val SESSION_NAME = "Push day"

        /** When the seeded workout started. Nothing here reads it; the row needs one. */
        const val STARTED_AT = 1_777_312_800_000L

        /** Any id at all: resolving a URI to an activity does not read the database. */
        const val SOME_SESSION_ID = 1L

        /*
         * The workout, by its header bar rather than by anything in its body — the same marker
         * `TitanAppNavigationTest` uses, and for the reason it records there: the bar is
         * drawn before the session's own Room reads come back.
         */
        const val ACTIVE_SESSION_MARKER = "End the session"

        /** Each screen's own eyebrow or title — the marker that does not move with the copy. */
        const val NEW_SESSION_MARKER = "New session"
        const val HOME_MARKER = "QUICK START"

        /**
         * How long a wait gives a screen to draw.
         *
         * The same figure `TitanAppNavigationTest` uses, for the same reason: every wait here
         * is on a Room read Compose's idling does not track, sharing a dispatcher with whatever
         * the test is driving.
         */
        const val READ_TIMEOUT_MILLIS = 5_000L
    }
}
