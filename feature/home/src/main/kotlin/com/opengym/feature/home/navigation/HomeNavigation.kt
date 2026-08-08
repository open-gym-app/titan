package com.opengym.feature.home.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.opengym.feature.home.HomeScreen
import kotlinx.serialization.Serializable

/** The Home tab's graph. Navigating here lands on [HomeDestination]. */
@Serializable
data object HomeGraphDestination

/** The dashboard. */
@Serializable
data object HomeDestination

/**
 * Home has one screen and still gets a nested graph.
 *
 * The bottom bar decides which tab is selected by asking whether the current destination
 * has a tab's route *anywhere in its hierarchy*. With a nested graph per tab that question
 * has one answer for every screen the tab will ever contain, including ones added later;
 * with a flat destination it only works while the tab is one screen deep, and breaks
 * silently — the tab simply stops looking selected — the first time somebody pushes a
 * second screen onto it.
 *
 * @param onStartSession the user wants to begin a workout. Named for the event rather than
 *   the destination: this module cannot see `:feature:session`, and `:app` is what decides
 *   that starting a session means entering the session flow.
 */
fun NavGraphBuilder.homeGraph(
    onStartSession: () -> Unit,
) {
    navigation<HomeGraphDestination>(startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeScreen(onStartSession = onStartSession)
        }
    }
}
