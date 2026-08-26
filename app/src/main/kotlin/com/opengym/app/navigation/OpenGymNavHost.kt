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
import com.opengym.feature.history.navigation.TemplateEditor
import com.opengym.feature.history.navigation.TemplateEditorDestination
import com.opengym.feature.history.navigation.exerciseDetailGraph
import com.opengym.feature.history.navigation.exercisePickerGraph
import com.opengym.feature.history.navigation.historyGraph
import com.opengym.feature.history.navigation.libraryGraph
import com.opengym.feature.history.navigation.plateCalculatorGraph
import com.opengym.feature.home.navigation.HomeGraphDestination
import com.opengym.feature.home.navigation.homeGraph
import com.opengym.feature.onboarding.navigation.OnboardingDestination
import com.opengym.feature.onboarding.navigation.onboardingGraph
import com.opengym.feature.progression.navigation.progressionGraph
import com.opengym.feature.session.navigation.ActiveSessionDestination
import com.opengym.feature.session.navigation.ExercisePicker
import com.opengym.feature.session.navigation.NO_TEMPLATE
import com.opengym.feature.session.navigation.NewSessionDestination
import com.opengym.feature.session.navigation.SessionGraphDestination
import com.opengym.feature.session.navigation.SessionSummaryDestination
import com.opengym.feature.session.navigation.sessionGraph
import com.opengym.feature.session.navigation.sessionSummaryGraph
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
 *
 * @param startsOnOnboarding whether this launch opens on the first-run flow rather than on the
 *   tabs. It is the graph's *start destination*, so it is read once — Navigation Compose
 *   rebuilds the graph and empties the back stack when it changes, and the value it is derived
 *   from flips at the end of onboarding. `MainViewModel` latches it for exactly that reason.
 */
@Composable
fun OpenGymNavHost(
    appState: OpenGymAppState,
    startsOnOnboarding: Boolean,
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
    //
    // Handed to the flow as a *function* rather than as its current value: the graph below is
    // built once, inside `NavHost`'s own `remember`, so any value read here is read at
    // build time — before the picker exists, let alone has answered. See
    // [ExercisePicker.selectedIds].
    var pickedExerciseIds by rememberSaveable { mutableStateOf<LongArray?>(null) }

    // Whether the picker now on screen is the one the session flow *opened on*.
    //
    // "Start an empty session" pushes setup and setup pushes the picker over itself before it
    // has drawn a frame — see `NewSessionRoute`'s entry effect. The setup screen is therefore
    // never seen on the way in, but it is on the back stack, and backing out of the picker
    // used to reveal it: a screen the user has not visited, wearing Home's own quick-start
    // action and template strip, which reads as the back gesture going *forwards*.
    //
    // Held here because it is a fact about how the flow was entered, which is the one thing
    // `:app` knows and neither feature does. A flag rather than a route argument on the
    // picker: the picker is `:feature:history`'s destination and has no business carrying a
    // session concept, and `NewSessionDestination.pickExercises` cannot stand in for it —
    // the argument outlives the entry it describes, so a picker reopened by hand from the
    // template list would answer to it too.
    //
    // `rememberSaveable` alongside the answer above, and for the same reason: the picker is a
    // screen a process death can happen behind.
    var pickerOpenedTheFlow by rememberSaveable { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = if (startsOnOnboarding) OnboardingDestination else TabsGraphDestination,
        modifier = modifier,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        onboardingGraph(
            // Home, with the flow dropped behind it. The `popUpTo` is the acceptance
            // criterion: a back gesture from Home after setting the app up must leave the
            // app, not put four questions the user has just answered back on the screen.
            //
            // Registered whether or not this launch started on it. A graph whose destinations
            // depend on which one it starts at is a graph that behaves differently on the
            // second launch, and the entry is inert on every launch that does not open here.
            onFinished = {
                navController.navigate(TabsGraphDestination) {
                    popUpTo<OnboardingDestination> { inclusive = true }
                }
            },
        )

        navigation<TabsGraphDestination>(startDestination = HomeGraphDestination) {
            homeGraph(
                // Straight into the workout, not through the flow that sets one up: the
                // session already exists, and `ActiveSessionViewModel` is what picks it back
                // up. Navigating to a destination *inside* the session graph puts the graph on
                // the back stack around it, so [exitSessionFlow] still takes the user out of
                // the mode from there.
                onResumeSession = { sessionId ->
                    navController.navigate(ActiveSessionDestination(sessionId)) {
                        launchSingleTop = true
                    }
                },
                // The three ways into setup, which differ only in where the flow opens — see
                // [NewSessionDestination]. `launchSingleTop` on all of them because the
                // triggers are full-width buttons and a scrolling strip of cards: two taps
                // dispatched before recomposition are two pushes of the same screen, and the
                // first back press then looks like it did nothing.
                onStartEmptySession = {
                    pickerOpenedTheFlow = true
                    navController.openSetup(pickExercises = true)
                },
                onStartFromTemplates = { navController.openSetup() },
                onStartFromTemplate = { templateId ->
                    navController.openSetup(templateId = templateId)
                },
                // A tab switch and not a push: the sessions the week card counts are the
                // Sessions tab's own list, and moving the user there through
                // [OpenGymAppState.navigateToTopLevelDestination] is what leaves that tab on
                // the stack it already had — and what makes the bar show where they now are.
                onOpenSessions = {
                    appState.navigateToTopLevelDestination(TopLevelDestination.SESSIONS)
                },
            )
            historyGraph(
                // A logged session opens the recap `:feature:session` draws, in its historical
                // mode — the same screen a workout ends on. Two features in one expression,
                // which is what this file is for.
                //
                // `launchSingleTop` as everywhere else: two taps on a history row are two
                // pushes of the same recap, and the first back press then looks like it did
                // nothing.
                onSessionClick = { sessionId ->
                    navController.navigate(
                        SessionSummaryDestination(sessionId = sessionId, historical = true),
                    ) {
                        launchSingleTop = true
                    }
                },
                // The same move Home's template action makes, through the same helper: the
                // session list has no template in hand either, so the flow opens on its list.
                // `openSetup` is what dedupes two taps dispatched in one frame, which is why
                // this no longer navigates to the graph itself.
                onStartSession = { navController.openSetup() },
                templateEditor = TemplateEditor(
                    // Inside the tab, unlike everything else pushed from here: a template is
                    // edited from the list of templates, and the list is a tab.
                    // `launchSingleTop` as everywhere else — two taps on a row are two pushes of
                    // the same editor, and the first back press then looks like it did nothing.
                    open = { templateId ->
                        navController.navigate(TemplateEditorDestination(templateId)) {
                            launchSingleTop = true
                        }
                    },
                    // The same editor with nothing in it, which is what an absent id means —
                    // see `NEW_TEMPLATE`. `launchSingleTop` for the reason above.
                    create = {
                        navController.navigate(TemplateEditorDestination()) {
                            launchSingleTop = true
                        }
                    },
                    onExit = { navController.popBackStack() },
                    // The same picker the session flow opens, and the same held answer. Only one
                    // of the two callers can be on the back stack at a time — the editor is
                    // inside the tabs and the flow is a mode over them — so there is no question
                    // of both reading it.
                    openExercisePicker = { replacing ->
                        navController.navigate(ExercisePickerDestination(replacing = replacing)) {
                            launchSingleTop = true
                        }
                    },
                    pickedExerciseIds = { pickedExerciseIds?.toList() },
                    onPickedExercisesHandled = { pickedExerciseIds = null },
                ),
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
                open = { replacing ->
                    navController.navigate(ExercisePickerDestination(replacing = replacing)) {
                        launchSingleTop = true
                    }
                },
                selectedIds = { pickedExerciseIds?.toList() },
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
            // The recap is not in the session graph — see `SessionSummaryDestination` — so
            // finishing both leaves the flow and opens it, in one move. Popping the graph
            // rather than the active session alone is what takes the user out of the mode:
            // the workout is over, and a bar-less screen for a session that no longer exists
            // is a mode with nothing left in it.
            onSessionFinished = { sessionId ->
                navController.navigate(SessionSummaryDestination(sessionId)) {
                    popUpTo<SessionGraphDestination> { inclusive = true }
                }
            },
            // A discarded workout has no recap to open — the row and its sets are gone — so
            // this is the flow being dropped and nothing being pushed in its place. The user
            // lands back wherever they started the session from, which is the honest answer
            // to "that did not happen".
            onSessionDiscarded = { navController.exitSessionFlow() },
        )

        // Registered once, for both callers. `onExit` means the same thing to the recap and
        // two different things here — the end of a workout, and a step back out of the session
        // list — and both are the same pop, because the flow above was already dropped on the
        // way in.
        sessionSummaryGraph(onExit = { navController.popBackStack() })

        exercisePickerGraph(
            // Cancelling the picker the flow opened on cancels the flow, rather than popping
            // one entry and landing the user on the setup screen they never saw. They asked
            // for a workout and changed their mind before choosing anything; the honest
            // answer to that is the tab they left, which is what [exitSessionFlow] returns
            // them to. Popping the graph from here reaches the picker as well — it sits above
            // it, outside the graph — so the mode goes in one move.
            //
            // Every other picker still pops one entry: opened from a running workout, from
            // the template editor, or by hand from the template list, it is a step inside
            // something the user is in the middle of, and leaving it is not leaving that.
            onBack = {
                if (pickerOpenedTheFlow) {
                    pickerOpenedTheFlow = false
                    navController.exitSessionFlow()
                } else {
                    navController.popBackStack()
                }
            },
            // The picker's result goes to whoever opened it, through the state hoisted above
            // — see the note on it for why this one does not travel on a `SavedStateHandle`
            // the way the detail screen's answer does. Recorded before the pop so the caller
            // is holding it by the time it is composed again.
            onExercisesSelected = { exerciseIds ->
                // The flow has been entered properly now: the setup screen the user is
                // dropped back onto is holding their plan, so it is somewhere they have been
                // and the back gesture out of it means what it says. A picker reopened later
                // from the template list must not inherit this entry's exit.
                pickerOpenedTheFlow = false
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
private fun NavHostController.openExercise(
    exerciseId: Long,
    addToSession: Boolean = false,
) {
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
 * Open the flow that sets a workout up, at the step the caller asked for.
 *
 * One function for every entry into it, because it is one destination: Home's two quick-start
 * actions and its template strip differ only in the arguments — see [NewSessionDestination].
 *
 * **`launchSingleTop` is not enough on its own here**, unlike everywhere else in this file: it
 * collapses a push only when the route *and its arguments* match the entry already on top, and
 * these differ by argument by design. Two taps dispatched before recomposition — two fingers
 * landing on two different cards of the quick-start strip — are therefore two setup screens,
 * and the first back press looks like it did nothing. Popping any setup entry already on the
 * stack is what makes the flow single-instance whichever way it was opened, and it is a no-op
 * the ordinary time, when there is none.
 */
private fun NavHostController.openSetup(
    templateId: Long = NO_TEMPLATE,
    pickExercises: Boolean = false,
) {
    navigate(NewSessionDestination(templateId = templateId, pickExercises = pickExercises)) {
        popUpTo<NewSessionDestination> { inclusive = true }
        launchSingleTop = true
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
