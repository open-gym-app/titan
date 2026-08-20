package com.opengym.feature.session.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.opengym.feature.session.active.ActiveSessionEvent
import com.opengym.feature.session.active.ActiveSessionRoute
import com.opengym.feature.session.active.ActiveSessionViewModel
import com.opengym.feature.session.newsession.NewSessionRoute
import com.opengym.feature.session.summary.SessionSummaryRoute
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

/**
 * A session's recap (#27) — the payoff after a workout, and the record of one in history.
 *
 * **Outside the session graph, and outside the tab scaffold**, which is the one thing about
 * this destination worth reading twice. It is registered by [sessionSummaryGraph] rather than
 * inside [sessionGraph], because the recap is the *end* of the workout rather than a step of
 * it: the session has been closed out and its screens popped by the time this is pushed, and
 * leaving the flow registered around it would keep the user in a mode whose subject no longer
 * exists.
 *
 * That placement is also what lets the session list reach it. The alternative — a second
 * screen in `:feature:history` drawing the same recap — is a module edge the architecture
 * forbids and a second copy of a layout that has to keep agreeing with this one.
 *
 * @param historical whether this is the record of a past session rather than the result of one
 *   just finished. A route argument rather than two route types with the same shape, matching
 *   `ExerciseDetailDestination.addToSession` and for the same reason: a `NavController`
 *   resolves by route *pattern*, and two of those would be ambiguous. See
 *   [com.opengym.feature.session.summary.SessionSummaryMode] for what it changes.
 */
@Serializable
data class SessionSummaryDestination(val sessionId: Long, val historical: Boolean = false)

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
 * @param open push the picker. What that opens is `:app`'s business. The flag is what the
 *   picker is being opened *for*: `replacing` means one slot on an exercise already in the
 *   workout, which takes one exercise rather than a list — the picker draws itself accordingly,
 *   so the screen cannot come back with two answers to a question that has one.
 * @param selectedIds the exercises chosen, in the order they were picked, or null when the
 *   picker has not answered. One of them when it was opened replacing.
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
    val open: (replacing: Boolean) -> Unit,
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
 *   Reached from **two** destinations now: setup opens it to build a plan, and the workout opens
 *   it to add to one. Only one of the two is ever on the back stack — setup is popped inclusive
 *   on the way into the workout — so there is no question of both reading the same answer.
 * @param onSessionStarted setup finished; the id is the session that was created.
 * @param onSessionFinished the workout ended and should be recapped. The recap is not in this
 *   graph — see [SessionSummaryDestination] — so this both leaves the flow and opens it.
 * @param onSessionDiscarded the workout was thrown away. A separate exit from
 *   [onSessionFinished] rather than the same one with a null id: there is no recap of a session
 *   that no longer exists, so the two land in different places and only `:app` knows where.
 */
fun NavGraphBuilder.sessionGraph(
    onCancelSetup: () -> Unit,
    exercisePicker: ExercisePicker,
    onSessionStarted: (sessionId: Long) -> Unit,
    onSessionFinished: (sessionId: Long) -> Unit,
    onSessionDiscarded: () -> Unit,
) {
    navigation<SessionGraphDestination>(startDestination = NewSessionDestination) {
        composable<NewSessionDestination> {
            NewSessionRoute(
                onCancel = onCancelSetup,
                onSessionStarted = onSessionStarted,
                // Setup is building the plan from nothing, so every pick adds to it.
                onPickExercises = { exercisePicker.open(false) },
                // Read here, inside the destination, rather than taken off the graph as it was
                // built — see [ExercisePicker.selectedIds] for why that distinction is the
                // difference between the picker working and doing nothing at all.
                pickedExerciseIds = exercisePicker.selectedIds(),
                onPickedExercisesHandled = exercisePicker.onSelectionHandled,
            )
        }
        composable<ActiveSessionDestination> {
            val snackbarHostState = remember { SnackbarHostState() }
            val viewModel: ActiveSessionViewModel = hiltViewModel()

            // Hiding the bottom bar makes the workout a mode; this is what makes it one.
            // Setup was popped inclusive on the way in, so this is the only entry left inside
            // the graph — an unguarded back gesture pops the graph itself and abandons the
            // session on its way past, silently, which is the loss the mode exists to prevent.
            //
            // It is not swallowed, though. Back means "I want out", and the workout has two
            // ways out; the gesture raises the question of which, rather than doing nothing
            // and leaving the user to find END. The dialog's own dismissal is what a second
            // back press then lands on, so the gesture never becomes inert.
            //
            // Here rather than inside `ActiveSessionScreen` because it is a property of the
            // destination, not of the screen: `BackHandler` needs an
            // `OnBackPressedDispatcherOwner`, which a `@Preview` does not provide.
            BackHandler {
                viewModel.onEvent(ActiveSessionEvent.DiscardRequested)
            }

            ActiveSessionRoute(
                onFinishSession = onSessionFinished,
                onDiscardSession = onSessionDiscarded,
                onPickExercises = exercisePicker.open,
                // Read here, inside the destination, for the reason [ExercisePicker.selectedIds]
                // gives: taken off the graph as it was built, this is `null` forever.
                pickedExerciseIds = exercisePicker.selectedIds(),
                onPickedExercisesHandled = exercisePicker.onSelectionHandled,
                snackbarHostState = snackbarHostState,
                viewModel = viewModel,
            )
        }
    }
}

/**
 * The session recap, registered **once**, outside every graph — see
 * [SessionSummaryDestination].
 *
 * Both callers land on the same destination and differ only in
 * [SessionSummaryDestination.historical]: the session flow pushes it as a workout ends, and
 * the session list pushes it for a workout logged months ago.
 *
 * @param onExit the way off the screen. It means two different things and neither of them is
 *   this feature's to know: after a workout it is the end of the flow, and from the list it is
 *   a pop back to the list. `:app` resolves that, the same way it resolves every other edge
 *   between two features.
 */
fun NavGraphBuilder.sessionSummaryGraph(onExit: () -> Unit) {
    composable<SessionSummaryDestination> {
        SessionSummaryRoute(onExit = onExit)
    }
}
