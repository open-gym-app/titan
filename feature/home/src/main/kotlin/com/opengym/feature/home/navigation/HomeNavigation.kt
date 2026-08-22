package com.opengym.feature.home.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.opengym.feature.home.HomeRoute
import kotlinx.serialization.Serializable

/** The Home tab's graph. Navigating here lands on [HomeDestination]. */
@Serializable
data object HomeGraphDestination

/** The dashboard. */
@Serializable
data object HomeDestination

/**
 * Home has one screen and still gets a nested graph.
 *
 * The bottom bar decides which tab is selected by asking whether the current destination
 * has a tab's route *anywhere in its hierarchy*. With a nested graph per tab that question
 * has one answer for every screen the tab will ever contain, including ones added later;
 * with a flat destination it only works while the tab is one screen deep, and breaks
 * silently — the tab simply stops looking selected — the first time somebody pushes a
 * second screen onto it.
 *
 * Every parameter below is named for the *event*, not the destination: this module cannot see
 * `:feature:session`, and `:app` is what decides that resuming a workout means the active
 * session screen and that starting one means the setup flow.
 *
 * @param onResumeSession the workout the app is holding open should be picked back up; the id
 *   is the session to open. Home is one of only two places this can be asked from — the
 *   history lists finished sessions, so a workout the process died under is otherwise
 *   unreachable.
 * @param onStartEmptySession a workout with nothing planned in it: the user will pick the
 *   exercises as they go.
 * @param onStartFromTemplates the user wants to choose a template first.
 * @param onStartFromTemplate a template was chosen already, from the quick-start strip.
 *   Separate from [onStartFromTemplates] because it names a template and therefore skips the
 *   step that picks one — Home cannot know that the two land in the same flow, and does not
 *   need to.
 */
fun NavGraphBuilder.homeGraph(
    onResumeSession: (sessionId: Long) -> Unit,
    onStartEmptySession: () -> Unit,
    onStartFromTemplates: () -> Unit,
    onStartFromTemplate: (templateId: Long) -> Unit,
) {
    navigation<HomeGraphDestination>(startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(
                onResumeSession = onResumeSession,
                onStartEmptySession = onStartEmptySession,
                onStartFromTemplates = onStartFromTemplates,
                onStartFromTemplate = onStartFromTemplate,
            )
        }
    }
}
