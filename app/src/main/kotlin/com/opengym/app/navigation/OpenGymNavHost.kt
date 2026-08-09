package com.opengym.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.opengym.app.ui.SettingsPlaceholderScreen
import com.opengym.feature.history.navigation.SessionDetailDestination
import com.opengym.feature.history.navigation.historyGraph
import com.opengym.feature.home.navigation.HomeGraphDestination
import com.opengym.feature.home.navigation.homeGraph
import com.opengym.feature.progression.navigation.progressionGraph
import com.opengym.feature.session.navigation.ActiveSessionDestination
import com.opengym.feature.session.navigation.NewSessionDestination
import com.opengym.feature.session.navigation.SessionGraphDestination
import com.opengym.feature.session.navigation.SessionSummaryDestination
import com.opengym.feature.session.navigation.sessionGraph

/**
 * The whole app graph — and the only place in the codebase where two features appear in the
 * same expression.
 *
 * Every lambda below is the same shape: a feature reports what happened, and this file
 * decides what that means. `:feature:home` says "the user wants to start a session"; the
 * fact that this opens a full-screen flow living in `:feature:session` is knowledge that
 * exists here and nowhere else. That is the whole reason features can be built, tested and
 * reasoned about without each other.
 */
@Composable
fun OpenGymNavHost(
    appState: OpenGymAppState,
    modifier: Modifier = Modifier,
) {
    val navController = appState.navController

    NavHost(
        navController = navController,
        startDestination = TabsGraphDestination,
        modifier = modifier,
    ) {
        navigation<TabsGraphDestination>(startDestination = HomeGraphDestination) {
            homeGraph(
                // `launchSingleTop` because the trigger is a full-width primary button. Two
                // taps dispatched before recomposition would push two nested session graphs,
                // and [exitSessionFlow] pops only the innermost — so CANCEL, or DONE at the
                // end of a real workout, would leave the user inside a second copy of the
                // flow with both bars hidden and no way out but the system back gesture.
                onStartSession = {
                    navController.navigate(SessionGraphDestination) { launchSingleTop = true }
                },
            )
            historyGraph(
                // Likewise: two taps on a history row are two pushes of the same detail
                // screen, and the first back press then looks like it did nothing.
                onSessionClick = { sessionId ->
                    navController.navigate(SessionDetailDestination(sessionId)) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
            progressionGraph()
        }

        sessionGraph(
            onCancelSetup = { navController.exitSessionFlow() },
            // Each step replaces the one before it. A workout is a one-way sequence, and
            // leaving setup on the stack would let the back gesture from an active session
            // land the user in the picker for a session that is already running.
            onSessionStarted = { sessionId ->
                navController.navigate(ActiveSessionDestination(sessionId)) {
                    popUpTo<NewSessionDestination> { inclusive = true }
                }
            },
            onSessionFinished = { sessionId ->
                navController.navigate(SessionSummaryDestination(sessionId)) {
                    popUpTo<ActiveSessionDestination> { inclusive = true }
                }
            },
            onSummaryDismissed = { navController.exitSessionFlow() },
        )

        composable<SettingsDestination> {
            SettingsPlaceholderScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Leave the session flow entirely, dropping every screen inside it.
 *
 * Popping the graph rather than the current destination is what returns the user to the tab
 * they came from with its stack intact, whichever step of the flow they were on.
 */
private fun NavHostController.exitSessionFlow() {
    popBackStack(SessionGraphDestination, inclusive = true)
}
