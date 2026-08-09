package com.opengym.feature.session.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.opengym.feature.session.ActiveSessionScreen
import com.opengym.feature.session.NewSessionScreen
import com.opengym.feature.session.R
import com.opengym.feature.session.SessionSummaryScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The session flow as a whole: set up → work out → recap.
 *
 * **This is not a tab.** It is a top-level destination sitting outside the tab scaffold,
 * and `:app` hides the bottom bar for every destination inside it. A workout is a mode, not
 * a place — offering a tap into Stats mid-set is an invitation to lose the set you were
 * logging, and the flow's own chrome (elapsed time, finish, discard) needs the room.
 *
 * `:app` detects the mode by looking for this route in the current destination's hierarchy,
 * which is why the flow is a nested graph rather than three sibling destinations: adding a
 * fourth screen to the flow later then needs no change in the shell.
 */
@Serializable
data object SessionGraphDestination

/** Template picking and exercise selection, before the clock starts (#25). */
@Serializable
data object NewSessionDestination

/** The workout in progress (#26). */
@Serializable
data class ActiveSessionDestination(val sessionId: Long)

/** The recap shown once the session is finished (#27). */
@Serializable
data class SessionSummaryDestination(val sessionId: Long)

/**
 * The session flow.
 *
 * Every one of these is hoisted, including the two that move *within* the flow. The
 * alternative — handing the feature a `NavController` so it can drive its own graph — also
 * hands it the ability to navigate anywhere else, and "features do not know about each
 * other" then holds only by convention. Here it holds because there is nothing to reach
 * for.
 *
 * @param onCancelSetup the user backed out before the session began.
 * @param onSessionStarted setup finished; the id is the session that was created.
 * @param onSessionFinished the workout ended and should be recapped.
 * @param onSummaryDismissed the recap was acknowledged — the flow is over.
 */
fun NavGraphBuilder.sessionGraph(
    onCancelSetup: () -> Unit,
    onSessionStarted: (sessionId: Long) -> Unit,
    onSessionFinished: (sessionId: Long) -> Unit,
    onSummaryDismissed: () -> Unit,
) {
    navigation<SessionGraphDestination>(startDestination = NewSessionDestination) {
        composable<NewSessionDestination> {
            NewSessionScreen(onCancel = onCancelSetup, onBeginSession = onSessionStarted)
        }
        composable<ActiveSessionDestination> { entry ->
            val destination: ActiveSessionDestination = entry.toRoute()

            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val blockedMessage = stringResource(R.string.session_back_blocked)

            // Hiding the bottom bar makes the workout a mode; this is what makes it one.
            // Setup was popped inclusive on the way in, so this is the only entry left
            // inside the graph — an unguarded back gesture pops the graph itself and
            // abandons the session on its way past, silently, which is the loss the mode
            // exists to prevent. Swallowing it is the whole guard for now; #26 replaces this
            // with the discard confirmation, once there are logged sets worth confirming the
            // loss of. FINISH is the way out until then.
            //
            // Swallowed, but not in silence: a back press that does nothing three times in a
            // row is indistinguishable from a frozen app, and from here the user cannot even
            // background it. The snackbar costs nothing and names the way out. It goes with
            // the guard when #26 replaces both.
            //
            // Here rather than inside [ActiveSessionScreen] because it is a property of the
            // destination, not of the screen: `BackHandler` needs an
            // `OnBackPressedDispatcherOwner`, which a `@Preview` does not provide.
            BackHandler {
                scope.launch {
                    // Dismiss first: `showSnackbar` suspends until the current one goes away,
                    // so without this an impatient triple-press queues three and the hint
                    // outlives the presses by seconds.
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = blockedMessage,
                        duration = SnackbarDuration.Short,
                    )
                }
            }

            ActiveSessionScreen(
                sessionId = destination.sessionId,
                onFinishSession = onSessionFinished,
                snackbarHostState = snackbarHostState,
            )
        }
        composable<SessionSummaryDestination> { entry ->
            val destination: SessionSummaryDestination = entry.toRoute()
            SessionSummaryScreen(sessionId = destination.sessionId, onDone = onSummaryDismissed)
        }
    }
}
