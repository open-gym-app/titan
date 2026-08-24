package com.opengym.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
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
     * - `launchSingleTop` stops a tab from stacking a second copy of itself.
     *
     * Drop any one and the bar stops behaving like tabs: without the first the back stack
     * grows without bound as the user browses, without the second every tab resets on every
     * visit.
     *
     * **Pressing the tab you are already in does nothing.** Not "nothing visible" — the call
     * returns before the navigator is touched, so the entry the user is looking at is never
     * popped, never rebuilt, and never re-animated. A bottom bar is a statement of where you
     * are as much as a way to move, and the answer to "take me to Home" while Home is on
     * screen is that you are there. Anything else spends a screen teardown, a lost scroll
     * position and an animation on a press that asked for no movement, and a bar that is
     * hammered or double-tapped does it repeatedly — which is what made the shell look broken.
     *
     * This deliberately gives up "tap the active tab to jump back to the top", the reselection
     * gesture Material's `NavigationBar` documents. It bought one thing here — a re-tap on the
     * Library closing a search and rewinding a long list — at the cost of every tab being a
     * screen the user can destroy by pressing the button that says they are already on it.
     * Nothing in the app is more than one destination deep inside its tab today, so the
     * gesture had nothing else to pop; the screens that scroll far enough to want it are
     * better served by a scroll-to-top of their own than by a rebuild.
     *
     * The guard reads the *hierarchy*, via [ShellChrome], and not the current destination: a
     * screen pushed inside a tab (#24) is still that tab, and the bar shows it as selected, so
     * pressing it there is the same press.
     */
    fun navigateToTopLevelDestination(topLevelDestination: TopLevelDestination) {
        val current = ShellChrome.of(navController.currentBackStackEntry?.destination)
        if (current.currentTopLevelDestination == topLevelDestination) return

        navController.navigate(topLevelDestination.graphDestination) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            restoreState = true
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
     * on purpose: settings and onboarding are outside the tabs too, and they are bar-less for
     * a weaker reason — they have no tab to keep selected, so [currentTopLevelDestination] is
     * already null there and [shouldShowBottomBar] is already false. Only the session flow
     * hides the bar from *inside* a tab's hierarchy, which is what makes it a mode. Anything
     * else that needs to be bar-less over a tab has to say so here.
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
