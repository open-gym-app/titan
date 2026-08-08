package com.opengym.app.navigation

import androidx.compose.runtime.Composable
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

    val currentDestination: NavDestination?
        @Composable get() = navController.currentBackStackEntryAsState().value?.destination

    /**
     * The tab the user is in, or `null` outside the tab scaffold.
     *
     * Matched against the whole hierarchy rather than the current destination alone, so a
     * screen pushed *inside* a tab — a session's detail, an exercise's progression — keeps
     * that tab looking selected instead of clearing the bar.
     */
    val currentTopLevelDestination: TopLevelDestination?
        @Composable get() {
            val destination = currentDestination ?: return null
            return TopLevelDestination.entries.firstOrNull { topLevel ->
                destination.hierarchy.any { it.hasRoute(topLevel.graphRoute) }
            }
        }

    /**
     * The session flow takes the whole screen.
     *
     * Phrased as "am I inside the session graph" rather than "am I inside the tab scaffold"
     * on purpose: those are the same thing today, but onboarding (#96) and settings are
     * also outside the tabs, and only one of the three should be able to hide the bar by
     * being a mode. Anything else that ends up bar-less should have to say so here.
     */
    val isInSessionFlow: Boolean
        @Composable get() = currentDestination?.hierarchy
            ?.any { it.hasRoute(SessionGraphDestination::class) } == true

    val shouldShowBottomBar: Boolean
        @Composable get() = currentTopLevelDestination != null && !isInSessionFlow

    /**
     * Switch tabs, preserving what each one had on its stack.
     *
     * The three options are one behaviour, not three preferences:
     *
     * - `popUpTo(start) { saveState = true }` empties the tab being left back down to the
     *   graph's start, but files its stack away under that tab's id;
     * - `restoreState = true` puts the arriving tab's filed stack back, so returning to
     *   Sessions lands on the session that was open rather than on the list;
     * - `launchSingleTop` stops re-tapping the current tab from stacking a second copy of it.
     *
     * Drop any one and the bar stops behaving like tabs: without the first the back stack
     * grows without bound as the user browses, without the second every tab resets on every
     * visit.
     */
    fun navigateToTopLevelDestination(topLevelDestination: TopLevelDestination) {
        navController.navigate(topLevelDestination.graphDestination) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

@Composable
fun rememberOpenGymAppState(
    navController: NavHostController = rememberNavController(),
): OpenGymAppState = remember(navController) { OpenGymAppState(navController) }
