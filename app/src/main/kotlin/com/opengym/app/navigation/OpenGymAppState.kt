package com.opengym.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opengym.feature.session.navigation.SessionGraphDestination

/**
 * What the shell needs to know about where the user is, and the one way it moves them
 * between tabs.
 *
 * Pulled out of the composable so the rules — which tab is selected, whether the bottom bar
 * belongs on screen — are stated once and read the same everywhere, rather than being three
 * subtly different `hierarchy.any { }` expressions inlined at three call sites.
 */
@Stable
class OpenGymAppState(val navController: NavHostController) {

    /**
     * The chrome for wherever the user currently is.
     *
     * One read of the back stack, not three. `currentBackStackEntryAsState()` opens its own
     * collector and registers its own `OnDestinationChangedListener` on every call, so the
     * three rules as three `@Composable` getters cost three of each per composition of the
     * shell — and the third was created and disposed as [ShellChrome.shouldShowBottomBar]'s
     * `&&` short-circuited. Answering them together is also the stronger reading of "stated
     * once": they cannot disagree about which destination they were derived from.
     */
    val chrome: ShellChrome
        @Composable get() = ShellChrome.of(
            navController.currentBackStackEntryAsState().value?.destination,
        )

    /**
     * Switch tabs, preserving what each one had on its stack.
     *
     * The three options are one behaviour, not three preferences:
     *
     * - `popUpTo(start) { saveState = true }` empties the tab being left back down to the
     *   graph's start, but files its stack away — and its entries' state with it — under that
     *   tab's id;
     * - `restoreState = true` puts the arriving tab's filed stack back, so returning to the
     *   Library lands on the search the user had open rather than on a fresh list;
     * - `launchSingleTop` stops re-tapping the current tab from stacking a second copy of it.
     *
     * Drop any one and the bar stops behaving like tabs: without the first the back stack
     * grows without bound as the user browses, without the second every tab resets on every
     * visit.
     *
     * **Except when the tab is already the one you are in.** Then it is not a switch at all:
     * the tab is popped and pushed back, saving and restoring nothing, and that is the whole
     * of "tap the active tab to go back to the top" — a re-tap on the Library from a filtered,
     * searched list lands on a fresh one, because the entry the search lived on is gone.
     *
     * The pop is of *this tab's own graph*, inclusively, and it has to be. Popping to the
     * app's start destination instead leaves Home — which is that start destination — with
     * nothing above it to pop, so `launchSingleTop` swaps the entry out in place; the outgoing
     * entry never becomes a transition, `NavHost` finds it missing from its visible entries
     * and falls back to a `ContentTransform` whose default `SizeTransform` wipes the screen
     * open from its top-left corner. That wipe is the one animation in the app nothing asked
     * for, and it is the reason a hammered Home button looks broken. Popping the tab's graph
     * makes every tab take the same path Sessions, Library and Stats already took, which is
     * the path where [NavMotion.RESET] — deliberately nothing — is what gets drawn.
     *
     * This is a deliberate exception to "returning to a tab resumes where you left it",
     * because they answer different questions. Arriving from elsewhere, the user is coming
     * back to what they were doing. Pressing the tab they are already on, they are asking for
     * the one thing that press could otherwise never do.
     *
     * **And only once they are actually on it.** A tab the shell is still animating *into* is
     * not a tab the user is on, but the back stack says it is the moment `navigate` returns —
     * so the second tap of a double tap reads as a reselection and resets a screen that has
     * not finished arriving. That is what the bar does when its buttons are hammered: every
     * tap after the first tears the tab down and rebuilds it mid-arrival, cutting the fade
     * short and starting it again from a screen that was never on screen. An entry reaches
     * `RESUMED` only once its enter transition has completed, which is precisely the question,
     * and a tap that arrives before then is the one the shell is already carrying out.
     */
    fun navigateToTopLevelDestination(topLevelDestination: TopLevelDestination) {
        val currentEntry = navController.currentBackStackEntry
        val isCurrentTab =
            ShellChrome.of(currentEntry?.destination).currentTopLevelDestination ==
                topLevelDestination
        val hasArrived =
            currentEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

        if (isCurrentTab && !hasArrived) return

        navController.navigate(topLevelDestination.graphDestination) {
            if (isCurrentTab) {
                popUpTo(topLevelDestination.graphDestination) { inclusive = true }
            } else {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                restoreState = true
            }
            launchSingleTop = true
        }
    }
}

/**
 * The questions the shell asks of a destination, answered together from one back-stack read.
 */
@Immutable
data class ShellChrome(
    /**
     * The tab the user is in, or `null` outside the tab scaffold.
     *
     * Matched against the whole hierarchy rather than the current destination alone, so a
     * screen pushed *inside* a tab keeps that tab looking selected instead of clearing the
     * bar. Nothing in the app is such a screen today — #27 moved the session recap out to the
     * top level, and it was the last — but #24 gives the Sessions tab three destinations of
     * its own, and the walk is what keeps two of them from clearing the bar.
     */
    val currentTopLevelDestination: TopLevelDestination?,
    /**
     * The session flow takes the whole screen.
     *
     * Phrased as "am I inside the session graph" rather than "am I inside the tab scaffold"
     * on purpose: those are the same thing today, but onboarding (#96) and settings are
     * also outside the tabs, and only one of the three should be able to hide the bar by
     * being a mode. Anything else that ends up bar-less should have to say so here.
     */
    val isInSessionFlow: Boolean,
) {
    val shouldShowBottomBar: Boolean = currentTopLevelDestination != null && !isInSessionFlow

    companion object {
        fun of(destination: NavDestination?): ShellChrome = ShellChrome(
            currentTopLevelDestination = destination?.let { current ->
                TopLevelDestination.entries.firstOrNull { topLevel ->
                    current.hierarchy.any { it.hasRoute(topLevel.graphRoute) }
                }
            },
            isInSessionFlow = destination?.hierarchy
                ?.any { it.hasRoute(SessionGraphDestination::class) } == true,
        )
    }
}

@Composable
fun rememberOpenGymAppState(
    navController: NavHostController = rememberNavController(),
): OpenGymAppState = remember(navController) { OpenGymAppState(navController) }
