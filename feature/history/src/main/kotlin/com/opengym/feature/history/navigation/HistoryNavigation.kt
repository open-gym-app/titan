package com.opengym.feature.history.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.opengym.feature.history.SessionDetailScreen
import com.opengym.feature.history.SessionsScreen
import kotlinx.serialization.Serializable

/** The Sessions tab's graph. Navigating here lands on [SessionsDestination]. */
@Serializable
data object HistoryGraphDestination

/** The session browser — history, templates and calendar (#24). */
@Serializable
data object SessionsDestination

/** One logged session, in read-only view mode. */
@Serializable
data class SessionDetailDestination(val sessionId: Long)

/**
 * The Sessions tab.
 *
 * @param onSessionClick a row was tapped. Handled inside this tab, but still hoisted: the
 *   `NavController` belongs to `:app`, and a feature that reaches for it directly is one
 *   refactor away from reaching for another feature's destination through it.
 * @param onBack leave the detail screen.
 */
fun NavGraphBuilder.historyGraph(
    onSessionClick: (sessionId: Long) -> Unit,
    onBack: () -> Unit,
) {
    navigation<HistoryGraphDestination>(startDestination = SessionsDestination) {
        composable<SessionsDestination> {
            SessionsScreen(onSessionClick = onSessionClick)
        }
        composable<SessionDetailDestination> { entry ->
            val destination: SessionDetailDestination = entry.toRoute()
            SessionDetailScreen(sessionId = destination.sessionId, onBack = onBack)
        }
    }
}
