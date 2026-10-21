package com.titan.feature.history.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.titan.feature.history.detail.ExerciseDetailRoute
import com.titan.feature.history.library.ExerciseLibraryRoute
import com.titan.feature.history.plate.PlateCalculatorRoute
import com.titan.feature.history.sessions.SessionsRoute
import com.titan.feature.history.template.TemplateEditorRoute
import kotlinx.serialization.Serializable

/** The Sessions tab's graph. Navigating here lands on [SessionsDestination]. */
@Serializable
data object HistoryGraphDestination

/** The session browser — history, templates and calendar (#24). */
@Serializable
data object SessionsDestination

/*
 * There is no `SessionDetailDestination` here, and that is deliberate.
 *
 * A logged session opens on the recap `:feature:session` draws — the same screen a workout
 * ends on, with `SessionSummaryDestination.historical` set. A second screen in this module
 * showing the same stats, records and muscle breakdown would be a copy that has to keep
 * agreeing with the original, and the module edge that would let this one call the original
 * is the one the architecture forbids. `:app` owns that edge, so `:app` makes the push; see
 * `historyGraph`'s `onSessionClick`.
 */

/**
 * One saved template, opened to be changed (#92).
 *
 * **Inside the Sessions tab**, unlike the exercise detail and the plate calculator, which are
 * modes pushed over everything. A template is read and edited *from the list of templates*, and
 * the list is a tab: the bottom bar stays, the back arrow goes back to the tab it came from, and
 * nothing about editing a plan is the kind of thing the session flow's chrome-less mode is for.
 * It is the second destination in that graph, which is why the graph existed before it did.
 *
 * @param templateId the template to edit, or [NEW_TEMPLATE] to build one from nothing. An id
 *   that no longer resolves draws the dead end rather than an empty editor — see
 *   `TemplateEditorViewModel`.
 */
@Serializable
data class TemplateEditorDestination(val templateId: Long = NEW_TEMPLATE)

/**
 * "There is no template yet", as a route argument.
 *
 * The editor is the only place a template is built from nothing — the other way one comes into
 * existence is a finished workout saved as one — so "create" and "edit" are the same screen with
 * the same draft, the same rules and the same SAVE, differing only in which use case that SAVE
 * calls. A second destination would be a second copy of all of it.
 *
 * A sentinel rather than a nullable `Long`, for the reason [NO_SUGGESTED_TARGET] is one:
 * Navigation Compose has no built-in `NavType` for a nullable primitive, and zero is impossible
 * as a template id because it is a row id the store assigns from one.
 */
const val NEW_TEMPLATE: Long = 0L

/** The Library tab's graph. Navigating here lands on [ExerciseLibraryDestination]. */
@Serializable
data object LibraryGraphDestination

/** The exercise catalogue, browsed. The Library tab's root. */
@Serializable
data object ExerciseLibraryDestination

/**
 * One exercise: its photo, what it works, how to do it, and the user's own note (#94).
 *
 * **Outside the tab scaffold**, unlike the two screens that reach it. It is a full-screen
 * reading surface with its own bar — the shell's "Library" title and settings gear are not
 * what somebody looking at a bench press needs, and neither is a bottom bar offering a jump
 * to Stats — and the wireframes draw it that way. One registration rather than one per
 * caller, which is also what lets [addToSession] be a route argument instead of two route
 * types with the same shape; `NavController` resolves by route *pattern*, and two of those
 * would be ambiguous.
 *
 * @param addToSession whether the screen offers "Add to session". True exactly when it was
 *   opened from the exercise picker, which is the only caller with a session being assembled
 *   to add to.
 */
@Serializable
data class ExerciseDetailDestination(
    val exerciseId: Long,
    val addToSession: Boolean,
)

/**
 * The plate calculator (#95).
 *
 * Outside the tab scaffold, like the exercise detail and for the same reasons: it is a
 * full-screen tool with its own bar, and both of the places it is reached from — an exercise's
 * detail screen, and the active session while a weight is being entered — are themselves pushed
 * over whatever the user was doing.
 *
 * It lives in this module rather than in `:feature:session` because the exercise detail screen
 * is the entry point that exists today and a destination is registered once. The session flow
 * reaches it the way `:app` reaches everything else, by naming this destination.
 *
 * @param target a weight to open on, in milligrams, or [NO_SUGGESTED_TARGET] for none. The
 *   active session passes the weight being typed, so the calculator opens on the number the user
 *   was already looking at rather than on a default they then have to step away from.
 */
@Serializable
data class PlateCalculatorDestination(val target: Long = NO_SUGGESTED_TARGET)

/**
 * "Nobody suggested a weight", as a route argument.
 *
 * A sentinel rather than a nullable `Long`, which Navigation Compose has no built-in `NavType`
 * for — a custom one would be a serializer and a registration for a value that has a perfectly
 * good impossible case. Zero is impossible because a bar weighs something: see
 * [com.titan.core.domain.model.UserPreferences.barWeight].
 */
const val NO_SUGGESTED_TARGET: Long = 0L

/**
 * The exercise catalogue, used to pick from.
 *
 * A destination of its own rather than a `selecting` flag on [ExerciseLibraryDestination],
 * because the two are in different places: browsing is a tab the user lives in, picking is a
 * screen pushed over a workout that returns a result and closes. One route registered in two
 * graphs is also ambiguous to the `NavController`, which resolves by route pattern.
 *
 * The screen behind both is still one screen — see [ExerciseLibraryRoute].
 *
 * @param replacing whether the picker is being opened to fill **one** slot — a swap on an
 *   exercise already in the workout — rather than to add to it. A slot holds one exercise, and
 *   a picker that goes on offering checkboxes for a question with one answer is a screen that
 *   collects a second choice it is about to throw away. A route argument rather than a second
 *   destination with the same shape, matching [ExerciseDetailDestination.addToSession]: a
 *   `NavController` resolves by route pattern, and two of those would be ambiguous.
 */
@Serializable
data class ExercisePickerDestination(val replacing: Boolean = false)

/**
 * The template editor, as the Sessions tab is allowed to see it.
 *
 * Five things that only make sense together — a way in, a way out, and the exercise picker's
 * three halves — so they travel as one parameter rather than as five. That is also what keeps
 * [historyGraph]'s signature readable as the tab grows.
 *
 * Every one of them is hoisted even though the editor is *this* module's own screen and the
 * picker's destination is declared a few lines above: the `NavController` belongs to `:app`, and
 * a feature that reaches for it to make one push is one refactor away from reaching for another
 * feature's destination through it.
 *
 * @param open a template row was tapped; the id is the template to edit.
 * @param create the templates tab's `+ NEW`, and the way out of an empty one. Opens the same
 *   editor on nothing — see [NEW_TEMPLATE].
 * @param onExit the editor is done with — saved, discarded, or opened on a template that is not
 *   there. One way out rather than three, because all three are the same pop and the screen has
 *   already said which of them happened.
 * @param openExercisePicker push the picker over the editor, saying which of the two questions it
 *   is being asked — see [ExercisePickerDestination.replacing]. Its destination is in this module,
 *   but it is registered *outside* the tab scaffold, so the push is `:app`'s.
 * @param pickedExerciseIds what the picker came back with, or null when it has not answered.
 *
 *   **A function, not the list itself, and this is load-bearing.** `NavHost` builds its graph
 *   inside a `remember`, so a plain `List<Long>?` here is the one that was there when the graph
 *   was built — `null`, always, since the picker cannot have answered before it has been opened.
 *   Called from inside the destination's own composition, the read lands in a scope that
 *   recomposes when the answer arrives. Same shape, and same reason, as `ExercisePicker` in
 *   `:feature:session`.
 * @param onPickedExercisesHandled the answer has been applied and should be forgotten, so a
 *   recomposition or a process restore does not append the same exercises twice.
 */
data class TemplateEditor(
    val open: (templateId: Long) -> Unit,
    val create: () -> Unit,
    val onExit: () -> Unit,
    val openExercisePicker: (replacing: Boolean) -> Unit,
    val pickedExerciseIds: () -> List<Long>?,
    val onPickedExercisesHandled: () -> Unit,
)

/**
 * The Sessions tab.
 *
 * Still **one** destination after #24, which split the tab into history, templates and
 * calendar: the three are tabs *within* one screen rather than three destinations, because
 * they are three readings of the same data and the user moves between them constantly — a day
 * tapped on the calendar is a filter on the history. As separate destinations that tap would
 * be a navigation with an argument, and the back stack would fill with screens the user was
 * only passing through. See [com.titan.feature.history.sessions.SessionsScreen].
 *
 * It remains a graph rather than a bare destination all the same: #92's template editor is a
 * second destination inside this tab, and a tab that grows one later should not have to be
 * converted then.
 *
 * @param onSessionClick a row was tapped. Hoisted, like every other navigation action: the
 *   `NavController` belongs to `:app`, and a feature that reaches for it directly is one
 *   refactor away from reaching for another feature's destination through it. Here that is
 *   load-bearing rather than stylistic — the destination this opens is in another module.
 * @param onStartSession the header's `+ NEW`, and the way out of an empty history. Opens the
 *   new-session flow, which is `:feature:session`'s — hoisted for the same reason.
 * @param templateEditor the second destination in this tab, and everything it needs to be
 *   reached and left — see [TemplateEditor].
 */
fun NavGraphBuilder.historyGraph(
    onSessionClick: (sessionId: Long) -> Unit,
    onStartSession: () -> Unit,
    templateEditor: TemplateEditor,
) {
    navigation<HistoryGraphDestination>(startDestination = SessionsDestination) {
        composable<SessionsDestination> {
            SessionsRoute(
                onSessionClick = onSessionClick,
                onStartSession = onStartSession,
                onTemplateClick = templateEditor.open,
                onNewTemplate = templateEditor.create,
            )
        }

        composable<TemplateEditorDestination> {
            TemplateEditorRoute(
                onExit = templateEditor.onExit,
                onPickExercises = templateEditor.openExercisePicker,
                // Read here, inside the destination, rather than taken off the graph as it was
                // built — see [TemplateEditor.pickedExerciseIds] for why that distinction is the
                // difference between the picker working and doing nothing at all.
                pickedExerciseIds = templateEditor.pickedExerciseIds(),
                onPickedExercisesHandled = templateEditor.onPickedExercisesHandled,
            )
        }
    }
}

/**
 * The Library tab.
 *
 * A tab and not a screen you go to and come back from, because it is the only place the 873
 * seeded exercises can be read and it is where a custom one is made. Behind a push it was a
 * destination with a back button at the top of an 873-row list, which is a long way from the
 * way out; as a tab the bottom bar is the way out and it never leaves the screen.
 *
 * It remains a graph rather than a bare destination, even though the one screen it pushes —
 * the exercise detail — is registered outside the tabs and not in here. A tab that grows a
 * second destination inside it later should not have to be converted then; see
 * [ExerciseDetailDestination] for why that screen is not one of them.
 *
 * @param onExerciseClick open an exercise's detail screen. Null draws the rows inert rather
 *   than giving them a ripple that does nothing.
 */
fun NavGraphBuilder.libraryGraph(onExerciseClick: ((exerciseId: Long) -> Unit)? = null) {
    navigation<LibraryGraphDestination>(startDestination = ExerciseLibraryDestination) {
        composable<ExerciseLibraryDestination> {
            ExerciseLibraryRoute(onExerciseClick = onExerciseClick)
        }
    }
}

/**
 * The exercise detail screen, registered **once**, outside the tab scaffold.
 *
 * Both callers — the Library tab and the picker — land on the same destination and differ
 * only in [ExerciseDetailDestination.addToSession]. See that type for why it is a route
 * argument rather than two route types.
 *
 * @param onBack leave the screen. Also what a delete calls, since the row it was drawing is
 *   gone; the screen reports that as state and this graph does not have to know.
 * @param onAddToSession the user pressed "Add to session". Handled by `:app`, which writes
 *   the id onto the picker's entry and pops — see [ADDED_EXERCISE_ID_KEY].
 * @param onOpenPlateCalculator the shortcut into the plate calculator, which lives outside this
 *   graph — see [plateCalculatorGraph]. Hoisted like every other navigation action: the
 *   `NavController` belongs to `:app`.
 * @param onViewProgression the shortcut into this exercise's progression (#102), which lives in
 *   `:feature:progression`. Hoisted for the same reason and, here, load-bearing: naming that
 *   destination from this module is the module edge the architecture forbids, so `:app` makes
 *   the push and hands the id back through this lambda.
 */
fun NavGraphBuilder.exerciseDetailGraph(
    onBack: () -> Unit,
    onAddToSession: (exerciseId: Long) -> Unit,
    onOpenPlateCalculator: () -> Unit,
    onViewProgression: (exerciseId: Long) -> Unit,
) {
    composable<ExerciseDetailDestination> { entry ->
        val destination: ExerciseDetailDestination = entry.toRoute()
        ExerciseDetailRoute(
            onBack = onBack,
            // Null rather than a no-op: the screen draws the pinned bar only when there is
            // something for it to do.
            onAddToSession = if (destination.addToSession) {
                { onAddToSession(destination.exerciseId) }
            } else {
                null
            },
            onOpenPlateCalculator = onOpenPlateCalculator,
            onViewProgression = { onViewProgression(destination.exerciseId) },
        )
    }
}

/**
 * The plate calculator, registered **once**, outside the tab scaffold.
 *
 * @param onBack leave the screen. The only way out: the calculator answers a question and
 *   returns nothing, so there is no confirm and no result to hand back.
 */
fun NavGraphBuilder.plateCalculatorGraph(onBack: () -> Unit) {
    composable<PlateCalculatorDestination> {
        PlateCalculatorRoute(onBack = onBack)
    }
}

/**
 * The exercise picker, registered **outside** the tab scaffold.
 *
 * The same screen as the Library tab with `selecting` flipped, in the one place the tab
 * cannot be: over a workout, drawing its own top bar and a confirm bar, with the shell's
 * bars gone. A picker opened mid-session has no business offering a jump to Stats.
 *
 * Opened for two different questions, and it says which one it is asking: adding to a workout
 * takes any number of exercises, while a swap fills one slot — see
 * [ExercisePickerDestination.replacing], which is what turns the checkboxes into a single
 * choice.
 *
 * @param onBack the user left without picking anything — the arrow and the system gesture
 *   both, since a picker that means two different things by them would be a picker whose
 *   caller could not act on either. Most callers pop one entry; the session flow's own entry
 *   picker leaves the flow, which is why this is a callback and not a pop in here.
 * @param onExerciseClick open an exercise's detail screen, to check it before picking it.
 * @param onExercisesSelected the picker's result, in the order the user picked them — which
 *   becomes the order of the exercises in the session. One id when it was opened replacing.
 */
fun NavGraphBuilder.exercisePickerGraph(
    onBack: () -> Unit,
    onExercisesSelected: (List<Long>) -> Unit,
    onExerciseClick: ((exerciseId: Long) -> Unit)? = null,
) {
    composable<ExercisePickerDestination> { entry ->
        val destination: ExercisePickerDestination = entry.toRoute()

        // The detail screen's answer, read off *this* destination's saved state — the entry
        // `:app` writes to as the detail pops. Read here rather than inside the screen
        // because this is the only place that has the `NavBackStackEntry`; the screen is
        // handed a value and a way to say it has used it.
        val handle = entry.savedStateHandle
        val addedExerciseId by handle
            .getStateFlow<Long?>(ADDED_EXERCISE_ID_KEY, null)
            .collectAsStateWithLifecycle()

        // The gesture and the arrow are one exit, so they run one lambda.
        //
        // Without this the gesture pops the back stack itself — which is what [onBack] does
        // for every caller but one, and so looked like the same thing for as long as it was.
        // It is not: a picker opened *as* the session flow's first screen has a setup screen
        // under it that the user has never seen, and leaving it means leaving the flow. That
        // is a decision only `:app` can make, and it can only make it if both ways out arrive
        // there — see `TitanNavHost`'s `pickerOpenedTheFlow`.
        //
        // Here rather than inside `ExerciseLibraryScreen` for the reason the active session's
        // handler gives: `BackHandler` needs an `OnBackPressedDispatcherOwner`, which a
        // `@Preview` does not provide.
        BackHandler { onBack() }

        ExerciseLibraryRoute(
            selecting = true,
            singleSelection = destination.replacing,
            onBack = onBack,
            onExerciseClick = onExerciseClick,
            onSelectionConfirmed = onExercisesSelected,
            addedExerciseId = addedExerciseId,
            onAddedExerciseHandled = { handle[ADDED_EXERCISE_ID_KEY] = null },
        )
    }
}

/*
 * The picker's *result* has no key here, deliberately.
 *
 * It was going to be a `SavedStateHandle` entry on the previous back stack entry, which is
 * the standard way a destination returns a value in Navigation Compose. That works for
 * [ADDED_EXERCISE_ID_KEY] below, whose reader is in this module — and does not work here,
 * because the readers are the new-session flow (#25) and, later, the template editor: the
 * first is `:feature:session`, which may not depend on this module and so could not name the
 * key. A constant duplicated on both sides with a comment asking nobody to change one of them
 * is not a contract.
 *
 * So `:app` holds the selection instead and hands it to whoever opened the picker as an
 * ordinary parameter — see `TitanNavHost`. It is the one module that knows both sides of
 * the edge, which is the whole reason it exists.
 */

/**
 * The key the detail screen's "Add to session" hands its exercise back under.
 *
 * The picker's own result travels the other way and by other means — see the note above for
 * why it cannot be a key at all. This one goes *into* the picker rather than out of it, and
 * both of its ends are in this module, which is what lets it stay a key. The detail screen is
 * pushed over the picker, so
 * the exercise it adds has to land in the selection the picker is still holding — otherwise
 * the button either loses the four exercises already ticked or has to confirm the whole
 * session on its own.
 *
 * Written by `:app` as the detail pops, since the write needs the `NavController`. Read by
 * [exercisePickerGraph] off its own entry, and cleared the moment it has been applied.
 */
const val ADDED_EXERCISE_ID_KEY = "com.titan.feature.history.addedExerciseId"
