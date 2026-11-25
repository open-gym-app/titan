package com.titan.feature.progression.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import com.titan.core.domain.navigation.DeepLinks
import com.titan.feature.progression.achievements.AchievementsRoute
import com.titan.feature.progression.body.BodyMeasurementsRoute
import com.titan.feature.progression.exercise.ExerciseProgressionRoute
import com.titan.feature.progression.photos.ProgressPhotosRoute
import com.titan.feature.progression.quests.QuestLogRoute
import com.titan.feature.progression.records.PersonalRecordsRoute
import com.titan.feature.progression.stats.StatsRoute
import com.titan.feature.progression.stats.component.StatsExploreActions
import kotlinx.serialization.Serializable

/** The Stats tab's graph. Navigating here lands on [StatsDestination]. */
@Serializable
data object ProgressionGraphDestination

/** The stats overview (#32) — the hub every other progression screen is reached from. */
@Serializable
data object StatsDestination

/**
 * The personal-record list (#103).
 *
 * **Outside the tab scaffold**, like the three below and like `:feature:history`'s exercise
 * detail: the wireframes draw it as a full-screen reading surface with its own bar and a way
 * back, and the Stats title with a bottom bar offering a jump to Home is not what somebody
 * reading a list of records needs.
 */
@Serializable
data object PersonalRecordsDestination

/**
 * Per-exercise progression (#102), read over the timeframe the hub is set to.
 *
 * @param exerciseId the lift to open on, or [NO_EXERCISE] for none. Two callers, and only one
 *   of them has an answer: an exercise's detail screen knows exactly which movement the user
 *   is looking at, while the hub's explore row is an invitation to drill into *any* of them.
 *   The screen opens on its own chooser for the second, which is also the switcher the first
 *   gets in its top bar — see `ExerciseProgressionScreen`.
 *
 *   A sentinel rather than a nullable `Long`, matching `PlateCalculatorDestination.target` and
 *   `TemplateEditorDestination.templateId`: Navigation Compose has no built-in `NavType` for a
 *   nullable primitive, and zero is impossible as a row id the store assigns from one.
 */
@Serializable
data class ExerciseProgressionDestination(val exerciseId: Long = NO_EXERCISE)

/** "No lift was named", as a route argument. See [ExerciseProgressionDestination.exerciseId]. */
const val NO_EXERCISE: Long = 0L

/**
 * The quest log (#104): level, XP, the streak and the quest board.
 *
 * Reached from the hub's explore section, and from outside the app entirely:
 * `titan://progression/quests` is where a progression notification lands, because this is the
 * one screen that draws both of the things it announces — the recent loot, and what is left of
 * the month's streak allowance. See [DeepLinks], which is where that URI is written down for
 * both ends of it.
 */
@Serializable
data object QuestLogDestination

/**
 * The achievements grid (#104), one push above the quest log.
 *
 * Its own destination because a milestone is its own kind of thing: a permanent, one-shot unlock
 * with no deadline, where a quest resets every period. See `AchievementsScreen`. Reached from the
 * quest log's own row and from nowhere else — it is deliberately *not* a sixth explore row, which
 * would put two gamification entries on the hub for what a user thinks of as one place.
 */
@Serializable
data object AchievementsDestination

/** Body measurements (#109). The hub is the only way in, as it is for the quest log. */
@Serializable
data object BodyMeasurementsDestination

/** The progress photos (#304), one push above the body measurements and reached only from them. */
@Serializable
data object ProgressPhotosDestination

/**
 * The Stats tab.
 *
 * One destination, which is what the hub is: the four drill-downs are registered by
 * [progressionDetailGraph] *outside* the tabs, so this graph holds the tab and nothing else.
 * It remains a graph rather than a bare destination all the same — the bottom bar matches
 * against it, and a tab that grows a second destination inside it later should not have to be
 * converted then.
 *
 * @param actions where each explore row goes. Hoisted, like every navigation action in this
 *   codebase: the `NavController` belongs to `:app`. Here that is load-bearing rather than
 *   stylistic for one of the five — the calendar row lands in `:feature:history`, an edge only
 *   `:app` is allowed to see.
 */
fun NavGraphBuilder.progressionGraph(actions: StatsExploreActions) {
    navigation<ProgressionGraphDestination>(startDestination = StatsDestination) {
        composable<StatsDestination> {
            StatsRoute(actions = actions)
        }
    }
}

/**
 * The screens the hub drills into, registered outside the tab scaffold.
 *
 * All five are real screens. They arrived one at a time behind a shared placeholder page,
 * which is why the destinations shipped with the hub rather than with the screens: a registration
 * is what an explore row navigates to, so replacing one was a change to the line below it and to
 * nothing else. #102 was the first to be replaced and #104 the last, and the placeholder went
 * with it.
 *
 * @param onBack a step back out. One lambda for all of them, because it is one pop.
 * @param onOpenExerciseProgression a record's exercise, opened on its own drill-down. Hoisted
 *   rather than navigated here: both destinations live in this graph, but which one a record row
 *   opens is a *routing* decision, and `:app` is where every other one of those is made.
 * @param onOpenAchievements the quest log's milestone row was tapped. Hoisted for the same
 *   reason, and named for the event rather than the destination as everything here is.
 * @param onOpenProgressPhotos the body screen's photos row was tapped. Hoisted for the same reason.
 * @param onOpenHealthConnect the body screen's Health Connect row was tapped. Hoisted because
 *   the screen it opens is the settings feature's, and features never reach each other.
 */
fun NavGraphBuilder.progressionDetailGraph(
    onBack: () -> Unit,
    onOpenExerciseProgression: (Long) -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenProgressPhotos: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    composable<PersonalRecordsDestination> {
        PersonalRecordsRoute(onBack = onBack, onOpenExercise = onOpenExerciseProgression)
    }
    composable<ExerciseProgressionDestination> {
        ExerciseProgressionRoute(onBack = onBack)
    }
    composable<QuestLogDestination>(
        // The whole URI rather than a base path with arguments appended: the destination takes
        // none, so there is nothing for Navigation to append.
        deepLinks = listOf(navDeepLink<QuestLogDestination>(basePath = DeepLinks.QUEST_LOG)),
    ) {
        QuestLogRoute(onBack = onBack, onOpenAchievements = onOpenAchievements)
    }
    composable<AchievementsDestination> {
        AchievementsRoute(onBack = onBack)
    }
    composable<BodyMeasurementsDestination> {
        BodyMeasurementsRoute(
            onBack = onBack,
            onOpenPhotos = onOpenProgressPhotos,
            onOpenHealthConnect = onOpenHealthConnect,
        )
    }
    composable<ProgressPhotosDestination> {
        ProgressPhotosRoute(onBack = onBack)
    }
}
