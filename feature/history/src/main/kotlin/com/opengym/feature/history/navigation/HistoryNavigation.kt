package com.opengym.feature.history.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.opengym.feature.history.SessionDetailScreen
import com.opengym.feature.history.SessionsScreen
import com.opengym.feature.history.library.ExerciseLibraryRoute
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
 * Nothing is pushed inside it yet — the exercise detail screen (#94) is the first thing that
 * will be — but it is registered as a graph rather than a bare destination so that when that
 * lands, the tab keeps its own back stack across a tab switch like the other three.
 *
 * @param onExerciseClick open an exercise's detail screen. **Null until #94 exists**: rows
 *   with nowhere to go are drawn inert rather than given a ripple that does nothing.
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
 * The exercise picker, registered **outside** the tab scaffold.
 *
 * The same screen as the Library tab with `selecting` flipped, in the one place the tab
 * cannot be: over a workout, drawing its own top bar and a confirm bar, with the shell's
 * bars gone. A picker opened mid-session has no business offering a jump to Stats.
 *
 * @param onExercisesSelected the picker's result, in the order the user picked them — which
 *   becomes the order of the exercises in the session.
 */
fun NavGraphBuilder.exercisePickerGraph(
    onBack: () -> Unit,
    onExercisesSelected: (List<Long>) -> Unit,
) {
    composable<ExercisePickerDestination> {
        ExerciseLibraryRoute(
            selecting = true,
            onBack = onBack,
            onSelectionConfirmed = onExercisesSelected,
        )
    }
}

/**
 * The key the picker's result is handed back under.
 *
 * A `SavedStateHandle` entry on the *previous* back stack entry, which is the standard way a
 * destination returns a value in Navigation Compose — there is no "start for result". The key
 * lives here, next to the destination that produces it, so the flow that reads it and the
 * screen that writes it cannot spell it differently.
 *
 * Written by `:app` when the library reports a confirmed selection, since the write needs the
 * `NavController` this module cannot see. Read by the new-session flow (#25) and the template
 * editor (#M4-18).
 */
const val SELECTED_EXERCISE_IDS_KEY = "com.opengym.feature.history.selectedExerciseIds"
