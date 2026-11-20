package com.titan.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.OnNewIntentProvider
import androidx.core.util.Consumer
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.navigation
import androidx.navigation.navOptions
import com.titan.app.BuildConfig
import com.titan.feature.history.navigation.ADDED_EXERCISE_ID_KEY
import com.titan.feature.history.navigation.ExerciseDetailDestination
import com.titan.feature.history.navigation.ExercisePickerDestination
import com.titan.feature.history.navigation.NO_SUGGESTED_TARGET
import com.titan.feature.history.navigation.PlateCalculatorDestination
import com.titan.feature.history.navigation.TemplateEditor
import com.titan.feature.history.navigation.TemplateEditorDestination
import com.titan.feature.history.navigation.exerciseDetailGraph
import com.titan.feature.history.navigation.exercisePickerGraph
import com.titan.feature.history.navigation.historyGraph
import com.titan.feature.history.navigation.libraryGraph
import com.titan.feature.history.navigation.plateCalculatorGraph
import com.titan.feature.home.navigation.HomeGraphDestination
import com.titan.feature.home.navigation.homeGraph
import com.titan.feature.onboarding.navigation.OnboardingDestination
import com.titan.feature.onboarding.navigation.onboardingGraph
import com.titan.feature.progression.navigation.AchievementsDestination
import com.titan.feature.progression.navigation.BodyMeasurementsDestination
import com.titan.feature.progression.navigation.ExerciseProgressionDestination
import com.titan.feature.progression.navigation.PersonalRecordsDestination
import com.titan.feature.progression.navigation.QuestLogDestination
import com.titan.feature.progression.navigation.progressionDetailGraph
import com.titan.feature.progression.navigation.progressionGraph
import com.titan.feature.progression.stats.component.StatsExploreActions
import com.titan.feature.session.navigation.ActiveSessionDestination
import com.titan.feature.session.navigation.ExercisePicker
import com.titan.feature.session.navigation.NO_TEMPLATE
import com.titan.feature.session.navigation.NewSessionDestination
import com.titan.feature.session.navigation.SessionGraphDestination
import com.titan.feature.session.navigation.SessionSummaryDestination
import com.titan.feature.session.navigation.sessionGraph
import com.titan.feature.session.navigation.sessionSummaryGraph
import com.titan.feature.settings.navigation.BackupDestination
import com.titan.feature.settings.navigation.HealthConnectDestination
import com.titan.feature.settings.navigation.OpenSourceLicencesDestination
import com.titan.feature.settings.navigation.ReminderDestination
import com.titan.feature.settings.navigation.settingsGraph

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
fun TitanNavHost(
    appState: TitanAppState,
    startsOnOnboarding: Boolean,
    modifier: Modifier = Modifier,
) {
    val navController = appState.navController

    val activity = LocalActivity.current

    // The link this launch was started by, taken off the activity **before** `NavHost` sees it.
    //
    // `NavHost` hands the launch intent to `NavController.setGraph`, which opens the link with
    // options of its own: it pops the whole graph *inclusive* and puts the target on an empty
    // stack. That is the "back reaches an empty task" this app must not do — a reminder tapped
    // from the lock screen would open the setup flow, and the first back press would leave the
    // app rather than landing on Home.
    //
    // Clearing the data is what stops that: `handleDeepLink` finds nothing to do, and
    // [openDeepLink] below opens it instead, over the tabs. Read in a `remember` keyed on the
    // activity so it happens once, on the composition that precedes `NavHost`'s own.
    val launchLink = remember(activity) { activity?.intent?.consumeDeepLink() }

    // Links that arrive while the app is already running.
    //
    // MainActivity is `singleTop`, so a notification tapped while the workout is on screen is
    // delivered to `onNewIntent` and nothing about the composition changes. Without this,
    // tapping the rest timer's notification with the app open brings the app forward and lands
    // wherever the user last was — which for the one case the link exists for is *usually*
    // right and silently wrong the rest of the time.
    //
    // Registered here rather than in `MainActivity` because this is where the `NavController`
    // is; the activity is reached through `OnNewIntentProvider`, which every `ComponentActivity`
    // implements. It goes through the same [openDeepLink] as the launch one, so a link opens
    // the same screen with the same stack under it however the app happened to be running.
    //
    // It is the only listener, and never calls `NavController.handleDeepLink`: that reads
    // Navigation's own deep-link extras off the intent, which `MainActivity` strips before this
    // sees it and which nothing here should be the second door for.
    DisposableEffect(activity, navController) {
        val provider = activity as? OnNewIntentProvider
        val listener = Consumer<Intent> { intent ->
            intent.consumeDeepLink()?.let(navController::openDeepLink)
        }
        provider?.addOnNewIntentListener(listener)
        onDispose { provider?.removeOnNewIntentListener(listener) }
    }

    // After the first composition, so the graph exists to match the link against.
    LaunchedEffect(launchLink, navController) {
        launchLink?.let(navController::openDeepLink)
    }

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
    // action and template list, which reads as the back gesture going *forwards*.
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
            // app, not put the questions the user has just answered back on the screen.
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
                // triggers are full-width buttons and rows of a list: two taps
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
                // A push over the tabs onto the board, the same move the Stats hub's explore
                // row makes and through the same helper — so back returns to Home with the tab
                // it was on still selected.
                onOpenQuestLog = { navController.openProgressionDetail(QuestLogDestination) },
                // A tab switch and not a push: the sessions the week card counts are the
                // Sessions tab's own list, and moving the user there through
                // [TitanAppState.navigateToTopLevelDestination] is what leaves that tab on
                // the stack it already had — and what makes the bar show where they now are.
                onOpenSessions = {
                    appState.navigateToTopLevelDestination(TopLevelDestination.SESSIONS)
                },
                // A push over the tabs into `:feature:progression`'s records list — the same
                // destination the hub's explore row opens, through the same helper. Home is the
                // second way in, and the only one that starts from a record the user just set.
                onOpenRecords = { navController.openProgressionDetail(PersonalRecordsDestination) },
                // A tab switch rather than a push, exactly as the week card's is: the chart is a
                // glance at the Stats tab's own hero, and moving the user there leaves that tab
                // on the stack it already had.
                onOpenStats = {
                    appState.navigateToTopLevelDestination(TopLevelDestination.STATS)
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
            progressionGraph(
                actions = StatsExploreActions(
                    // Four pushes over the tabs, each into a destination this feature owns and
                    // registers outside the scaffold — see [progressionDetailGraph].
                    // `launchSingleTop` as everywhere else: two taps on a row dispatched before
                    // recomposition are two pushes of the same screen, and the first back press
                    // then looks like it did nothing.
                    onOpenRecords = { navController.openProgressionDetail(PersonalRecordsDestination) },
                    // No lift named: the row is an invitation to drill into *any* of them, so
                    // the screen opens on its own chooser. The exercise detail screen is the
                    // caller that arrives with one — see `exerciseDetailGraph`.
                    onOpenExerciseProgression = {
                        navController.openProgressionDetail(ExerciseProgressionDestination())
                    },
                    onOpenQuestLog = { navController.openProgressionDetail(QuestLogDestination) },
                    onOpenMeasurements = {
                        navController.openProgressionDetail(BodyMeasurementsDestination)
                    },
                    // The fifth is the one that crosses a module edge, which is why it is
                    // hoisted at all: the calendar is the Sessions tab's own third pane
                    // (#24), so this is a tab switch and not a push — the same move Home's
                    // week card makes, through the same helper, and what leaves that tab on
                    // the stack it already had.
                    //
                    // It lands on whichever of the tab's three panes the user left it on.
                    // Opening it *on* the calendar means a route argument on
                    // `SessionsDestination`, and a tab switch restores the tab's saved state
                    // over any argument it is given — so the honest thing today is to take
                    // the user to the screen the calendar is on rather than to pretend.
                    onOpenCalendar = {
                        appState.navigateToTopLevelDestination(TopLevelDestination.SESSIONS)
                    },
                ),
            )
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
            // A workout page opened from a link to a session that is not running. The same pop
            // as a discard, and for the same reason: back to wherever the user was, with the
            // workout that *is* running left exactly as it was.
            onSessionLeft = { navController.exitSessionFlow() },
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
            // The one edge only this file may see: an exercise in `:feature:history` opening
            // its progression in `:feature:progression`. The screen it lands on is the same
            // one the hub's explore row opens, with the lift already named — see
            // [ExerciseProgressionDestination].
            onViewProgression = { exerciseId ->
                navController.openProgressionDetail(ExerciseProgressionDestination(exerciseId))
            },
        )

        plateCalculatorGraph(onBack = { navController.popBackStack() })

        // Outside the tabs, like the exercise detail and the plate calculator: all four are
        // full-screen reading surfaces with their own bar, and the Stats title over a list of
        // personal records is chrome for the wrong screen.
        progressionDetailGraph(
            onBack = { navController.popBackStack() },
            // A record opens the lift it was set on, with the lift already named — the same
            // screen the hub's explore row reaches empty. `launchSingleTop` as everywhere else:
            // two taps on a row dispatched before recomposition are two pushes of one screen.
            onOpenExerciseProgression = { exerciseId ->
                navController.navigate(ExerciseProgressionDestination(exerciseId)) {
                    launchSingleTop = true
                }
            },
            // A push onto the quest log rather than a replacement of it: the board is where the
            // user came from and the back press should put them back on it.
            onOpenAchievements = {
                navController.navigate(AchievementsDestination) { launchSingleTop = true }
            },
            // The settings feature's screen, pushed over the body screen: back returns to the
            // measurements, which is where the user came from.
            onOpenHealthConnect = {
                navController.navigate(HealthConnectDestination) { launchSingleTop = true }
            },
        )

        settingsGraph(
            // The version name belongs to the module that ships, and `:app` is it. A feature's
            // own `BuildConfig` carries the library's version, which is not what a user should
            // be quoting in a bug report.
            appVersion = BuildConfig.VERSION_NAME,
            onBack = { navController.popBackStack() },
            // Inside the settings slice, but pushed from here for the reason every other
            // destination is: the controller is `:app`'s. `launchSingleTop` as everywhere
            // else — two taps on the row before recomposition are two pushes of one screen.
            onOpenBackup = {
                navController.navigate(BackupDestination) { launchSingleTop = true }
            },
            // A push over settings, which is itself outside the tabs, and single-top for the
            // same reason as the row above it.
            onOpenReminders = {
                navController.navigate(ReminderDestination) { launchSingleTop = true }
            },
            // A push over settings, which is itself outside the tabs, and single-top for the
            // same reason as the row above it.
            onOpenLicences = {
                navController.navigate(OpenSourceLicencesDestination) { launchSingleTop = true }
            },
            // A push over settings, which is itself outside the tabs, and single-top for the
            // same reason as the row above it.
            onOpenHealthConnect = {
                navController.navigate(HealthConnectDestination) { launchSingleTop = true }
            },
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
 * Open one of the stats hub's drill-downs.
 *
 * One function for all four because they are one kind of move: a push over the tabs into a
 * destination `:feature:progression` owns. `launchSingleTop` for the reason every other push
 * here has it — two taps on an explore row dispatched before recomposition are two pushes of
 * the same screen, and the first back press then looks like it did nothing.
 */
private fun NavHostController.openProgressionDetail(destination: Any) {
    navigate(destination) { launchSingleTop = true }
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
 * actions and its template list differ only in the arguments — see [NewSessionDestination].
 *
 * **`launchSingleTop` is not enough on its own here**, unlike everywhere else in this file: it
 * collapses a push only when the route *and its arguments* match the entry already on top, and
 * these differ by argument by design. Two taps dispatched before recomposition — two fingers
 * landing on two different rows of the quick-start list — are therefore two setup screens,
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

/**
 * The `titan://` link this intent carries, taken off it so nothing opens it twice.
 *
 * Both callers need the *clearing* as much as the value. `NavHost` hands the launch intent to
 * `NavController.setGraph`, which opens any link on it with options that empty the back stack;
 * the `onNewIntent` listener would otherwise leave a link on the activity's intent for a later
 * recomposition to open again. Reading it once and blanking it is what makes [openDeepLink] the
 * only thing that ever acts on one.
 *
 * Only a `VIEW` intent carries one. `forNavigation`, in `MainActivity.kt`, has already dropped
 * the data off any other action, so the gate here and the one there agree:
 * `NavController.setGraph` would match a `titan://` URI whatever the action, and blanking only
 * `VIEW` data is enough because no other intent reaching this has any.
 *
 * Null for an ordinary launch — the launcher icon, or a return from the recents list.
 */
internal fun Intent.consumeDeepLink(): Uri? {
    if (action != Intent.ACTION_VIEW) return null
    return data?.also { data = null }
}

/**
 * Open [uri], leaving the tabs underneath it.
 *
 * ### Why this exists rather than `NavController.handleDeepLink`
 *
 * Because of the options that one uses. It pops the graph *inclusive* and puts the target on an
 * empty stack, which is right for an app whose deep links are its whole content and wrong for
 * this one: every link the app posts points at a screen that lives over the tabs, and a first
 * back press that leaves the app is the "empty task" #39's acceptance criteria rule out. The
 * `popUpTo` below is not inclusive, so the tabs stay under whatever the link opens and back
 * reaches Home.
 *
 * The `popUpTo` names the graph's **start destination** rather than the tabs graph around it,
 * and the distinction is the whole of whether this works: popping up to a *graph* leaves the
 * graph's own entry and pops the destination inside it, so the user lands on a route that draws
 * nothing. `findStartDestination` walks down to Home, which is the screen that has to be there.
 *
 * It also trims anything the user had pushed above Home, which is the right answer for the one
 * case it changes: a link tapped while the user is three screens deep opens the screen the link
 * names, rather than opening it on top of a stack they would have to back out of one entry at a
 * time. Back from a notification means "I am done with this", and the honest destination for
 * that is where the app opens.
 *
 * ### A link this build does not know does nothing
 *
 * `navigate` throws for a URI no destination matches — a link from a later version left in the
 * notification shade across a downgrade, or a hand-typed `adb` command. Nothing is worth
 * crashing the app the user has just opened, so [NavDestination.hasDeepLink] is asked first and
 * an unmatched link leaves them where they were.
 *
 * `internal` rather than private so `DeepLinkNavigationTest` can drive the running-app path
 * through it. That is not a seam opened for the test: it is the one funnel both real paths use,
 * and a test that reached the same behaviour some other way would be testing something else.
 */
internal fun NavHostController.openDeepLink(uri: Uri) {
    if (!graph.hasDeepLink(uri)) return

    navigate(
        NavDeepLinkRequest.Builder.fromUri(uri).build(),
        navOptions {
            popUpTo(graph.findStartDestination().id) { inclusive = false }
            launchSingleTop = true
        },
    )
}
