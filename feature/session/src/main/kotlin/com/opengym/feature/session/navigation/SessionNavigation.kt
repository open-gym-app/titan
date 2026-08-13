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
import com.opengym.feature.session.R
import com.opengym.feature.session.SessionSummaryScreen
import com.opengym.feature.session.newsession.NewSessionRoute
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
 * The exercise picker, as the session flow is allowed to see it.
 *
 * Three things that only make sense together — a way in, the answer, and a way to forget it
 * — so they travel as one parameter rather than as three. That is also what keeps
 * [sessionGraph]'s signature readable as the flow grows.
 *
 * The answer travels as a value rather than a `SavedStateHandle` key, which is how a
 * destination usually returns one in Navigation Compose: the picker lives in another feature
 * module, and a key named on both sides of that boundary is an edge the architecture forbids.
 * `:app` holds it instead and hands it over here — see `OpenGymNavHost`.
 *
 * @param open push the picker. What that opens is `:app`'s business.
 * @param selectedIds the exercises chosen, in the order they were picked, or null when the
 *   picker has not answered.
 *
 *   **A function, not the list itself, and this is load-bearing.** `NavHost` builds its graph
 *   inside a `remember` keyed on the builder lambda, so everything this value is constructed
 *   from is frozen at the moment the graph is first built. A plain `List<Long>?` here is the
 *   one that was there then — `null`, always, since the picker cannot have answered before it
 *   has been opened — and no later selection ever reaches the screen. Called from inside the
 *   destination's own composition, the read lands in a scope that recomposes when the answer
 *   arrives.
 * @param onSelectionHandled the answer has been applied and should be forgotten, so a
 *   recomposition or a process restore does not apply it twice.
 */
data class ExercisePicker(
    val open: () -> Unit,
    val selectedIds: () -> List<Long>?,
    val onSelectionHandled: () -> Unit,
)

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
 * @param exercisePicker the picker, as this flow is allowed to see it — see [ExercisePicker].
 * @param onSessionStarted setup finished; the id is the session that was created.
 * @param onSessionFinished the workout ended and should be recapped.
 * @param onSummaryDismissed the recap was acknowledged — the flow is over.
 */
fun NavGraphBuilder.sessionGraph(
    onCancelSetup: () -> Unit,
    exercisePicker: ExercisePicker,
    onSessionStarted: (sessionId: Long) -> Unit,
    onSessionFinished: (sessionId: Long) -> Unit,
    onSummaryDismissed: () -> Unit,
) {
    navigation<SessionGraphDestination>(startDestination = NewSessionDestination) {
        composable<NewSessionDestination> {
            NewSessionRoute(
                onCancel = onCancelSetup,
                onSessionStarted = onSessionStarted,
                onPickExercises = exercisePicker.open,
                // Read here, inside the destination, rather than taken off the graph as it was
                // built — see [ExercisePicker.selectedIds] for why that distinction is the
                // difference between the picker working and doing nothing at all.
                pickedExerciseIds = exercisePicker.selectedIds(),
                onPickedExercisesHandled = exercisePicker.onSelectionHandled,
            )
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
