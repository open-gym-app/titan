package com.opengym.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.navigation
import com.opengym.app.BuildConfig
import com.opengym.feature.history.navigation.ADDED_EXERCISE_ID_KEY
import com.opengym.feature.history.navigation.ExerciseDetailDestination
import com.opengym.feature.history.navigation.ExercisePickerDestination
import com.opengym.feature.history.navigation.NO_SUGGESTED_TARGET
import com.opengym.feature.history.navigation.PlateCalculatorDestination
import com.opengym.feature.history.navigation.SessionDetailDestination
import com.opengym.feature.history.navigation.exerciseDetailGraph
import com.opengym.feature.history.navigation.exercisePickerGraph
import com.opengym.feature.history.navigation.historyGraph
import com.opengym.feature.history.navigation.libraryGraph
import com.opengym.feature.history.navigation.plateCalculatorGraph
import com.opengym.feature.home.navigation.HomeGraphDestination
import com.opengym.feature.home.navigation.homeGraph
import com.opengym.feature.progression.navigation.progressionGraph
import com.opengym.feature.session.navigation.ActiveSessionDestination
import com.opengym.feature.session.navigation.ExercisePicker
import com.opengym.feature.session.navigation.NewSessionDestination
import com.opengym.feature.session.navigation.SessionGraphDestination
import com.opengym.feature.session.navigation.SessionSummaryDestination
import com.opengym.feature.session.navigation.sessionGraph
import com.opengym.feature.settings.navigation.settingsGraph

/**
 * The whole app graph — and the only place in the codebase where two features appear in the
 * same expression.
 *
 * Every lambda below is the same shape: a feature reports what happened, and this file
 * decides what that means. `:feature:home` says "the user wants to start a session"; the
 * fact that this opens a full-screen flow living in `:feature:session` is knowledge that
 * exists here and nowhere else. That is the whole reason features can be built, tested and
 * reasoned about without each other.
 *
 * The four transitions are set once, here, rather than per destination. They are not four
 * preferences: each one asks [motionBetween] what the move it is animating *means* and
 * answers accordingly, so a tab switch, a push and an entry into the session flow look
 * different from each other everywhere in the app without any destination having to opt in.
 * See [NavMotion].
 */
@Composable
fun OpenGymNavHost(
    appState: OpenGymAppState,
    modifier: Modifier = Modifier,
) {
    val navController = appState.navController

    // The exercise picker's answer, on its way back to whoever opened it.
    //
    // Held here rather than on the caller's `SavedStateHandle`, which is the usual way a
    // destination returns a value in Navigation Compose, because the caller and the picker
    // are in *different feature modules* and the key would have to be named in both — an
    // edge the architecture forbids. `:app` already knows both sides of every such edge, so
    // it holds the value instead and hands it to the caller as an ordinary parameter.
    //
    // `rememberSaveable` because the picker is a screen the user can sit on long enough for
    // the process to be killed behind it, and a selection lost to that is eight exercises
    // the user has to find again.
    var pickedExerciseIds by rememberSaveable { mutableStateOf<LongArray?>(null) }

    NavHost(
        navController = navController,
        startDestination = TabsGraphDestination,
        modifier = modifier,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
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
            libraryGraph(onExerciseClick = { exerciseId -> navController.openExercise(exerciseId) })
            progressionGraph()
        }

        sessionGraph(
            onCancelSetup = { navController.exitSessionFlow() },
            exercisePicker = ExercisePicker(
                // `launchSingleTop` as everywhere else: two taps on the quick-start dispatched
                // before recomposition are two pushes of the same picker, and the first back
                // press then looks like it did nothing.
                open = {
                    navController.navigate(ExercisePickerDestination) { launchSingleTop = true }
                },
                selectedIds = pickedExerciseIds?.toList(),
                onSelectionHandled = { pickedExerciseIds = null },
            ),
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

        exercisePickerGraph(
            onBack = { navController.popBackStack() },
            // The picker's result goes to whoever opened it, through the state hoisted above
            // — see the note on it for why this one does not travel on a `SavedStateHandle`
            // the way the detail screen's answer does. Recorded before the pop so the caller
            // is holding it by the time it is composed again.
            onExercisesSelected = { exerciseIds ->
                pickedExerciseIds = exerciseIds.toLongArray()
                navController.popBackStack()
            },
            // The same screen as the tab's rows open, with the one difference that matters
            // here: it offers to add what it is showing to the session being assembled.
            onExerciseClick = { exerciseId ->
                navController.openExercise(exerciseId, addToSession = true)
            },
        )

        exerciseDetailGraph(
            onBack = { navController.popBackStack() },
            // The mirror of the picker's own result, going the other way: the id lands on the
            // picker's entry — which is the previous one, since the detail is pushed straight
            // over it — and the pop is what makes the picker read it. Written before the pop,
            // for the reason above.
            onAddToSession = { exerciseId ->
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(ADDED_EXERCISE_ID_KEY, exerciseId)
                navController.popBackStack()
            },
            // No target: an exercise's detail screen knows the movement, not the weight the
            // user is working up to. The active session (#26) is the caller that has one.
            onOpenPlateCalculator = { navController.openPlateCalculator() },
        )

        plateCalculatorGraph(onBack = { navController.popBackStack() })

        settingsGraph(
            // The version name belongs to the module that ships, and `:app` is it. A feature's
            // own `BuildConfig` carries the library's version, which is not what a user should
            // be quoting in a bug report.
            appVersion = BuildConfig.VERSION_NAME,
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * Open an exercise's detail screen.
 *
 * One function for both callers, because the destination is one destination: the Library tab
 * and the picker differ only in whether the screen offers to add what it is showing to a
 * session — see `ExerciseDetailDestination`.
 *
 * `launchSingleTop` for the reason every other push here has it: two taps on a row dispatched
 * before recomposition are two pushes of the same screen, and the first back press then looks
 * like it did nothing.
 */
private fun NavHostController.openExercise(exerciseId: Long, addToSession: Boolean = false) {
    navigate(ExerciseDetailDestination(exerciseId = exerciseId, addToSession = addToSession)) {
        launchSingleTop = true
    }
}

/**
 * Open the plate calculator.
 *
 * `launchSingleTop` for the reason every other push here has it: two taps dispatched before
 * recomposition are two pushes of the same screen, and the first back press then looks like it
 * did nothing.
 *
 * @param target a weight to open on, or [NO_SUGGESTED_TARGET]. The parameter is here for the
 *   active session's weight entry (#26), which is the one caller that knows a number.
 */
private fun NavHostController.openPlateCalculator(target: Long = NO_SUGGESTED_TARGET) {
    navigate(PlateCalculatorDestination(target)) { launchSingleTop = true }
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
