package com.opengym.feature.history.navigation

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.opengym.feature.history.SessionDetailScreen
import com.opengym.feature.history.SessionsScreen
import com.opengym.feature.history.detail.ExerciseDetailRoute
import com.opengym.feature.history.library.ExerciseLibraryRoute
import com.opengym.feature.history.plate.PlateCalculatorRoute
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
data class ExerciseDetailDestination(val exerciseId: Long, val addToSession: Boolean)

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
 * [com.opengym.core.domain.model.UserPreferences.barWeight].
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
 */
@Serializable
data object ExercisePickerDestination

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
fun NavGraphBuilder.libraryGraph(
    onExerciseClick: ((exerciseId: Long) -> Unit)? = null,
) {
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
 */
fun NavGraphBuilder.exerciseDetailGraph(
    onBack: () -> Unit,
    onAddToSession: (exerciseId: Long) -> Unit,
    onOpenPlateCalculator: () -> Unit,
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
            // Still to be built — the per-exercise progression is M5 — and the screen draws no
            // button for a null.
            onViewProgression = null,
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
 * @param onExerciseClick open an exercise's detail screen, to check it before picking it.
 * @param onExercisesSelected the picker's result, in the order the user picked them — which
 *   becomes the order of the exercises in the session.
 */
fun NavGraphBuilder.exercisePickerGraph(
    onBack: () -> Unit,
    onExercisesSelected: (List<Long>) -> Unit,
    onExerciseClick: ((exerciseId: Long) -> Unit)? = null,
) {
    composable<ExercisePickerDestination> { entry ->
        // The detail screen's answer, read off *this* destination's saved state — the entry
        // `:app` writes to as the detail pops. Read here rather than inside the screen
        // because this is the only place that has the `NavBackStackEntry`; the screen is
        // handed a value and a way to say it has used it.
        val handle = entry.savedStateHandle
        val addedExerciseId by handle
            .getStateFlow<Long?>(ADDED_EXERCISE_ID_KEY, null)
            .collectAsStateWithLifecycle()

        ExerciseLibraryRoute(
            selecting = true,
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
 * ordinary parameter — see `OpenGymNavHost`. It is the one module that knows both sides of
 * the edge, which is the whole reason it exists.
 */

/**
 * The key the detail screen's "Add to session" hands its exercise back under.
 *
 * The same mechanism as [SELECTED_EXERCISE_IDS_KEY] and a different direction: this one goes
 * *into* the picker rather than out of it. The detail screen is pushed over the picker, so
 * the exercise it adds has to land in the selection the picker is still holding — otherwise
 * the button either loses the four exercises already ticked or has to confirm the whole
 * session on its own.
 *
 * Written by `:app` as the detail pops, since the write needs the `NavController`. Read by
 * [exercisePickerGraph] off its own entry, and cleared the moment it has been applied.
 */
const val ADDED_EXERCISE_ID_KEY = "com.opengym.feature.history.addedExerciseId"
