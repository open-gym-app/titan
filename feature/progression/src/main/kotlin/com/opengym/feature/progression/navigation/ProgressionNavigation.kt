package com.opengym.feature.progression.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.opengym.feature.progression.detail.ProgressionDetail
import com.opengym.feature.progression.detail.ProgressionDetailScreen
import com.opengym.feature.progression.stats.StatsRoute
import com.opengym.feature.progression.stats.component.StatsExploreActions
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

/** Per-exercise progression (#102), read over the timeframe the hub is set to. */
@Serializable
data object ExerciseProgressionDestination

/**
 * The quest log (#104): level, XP, quests, achievements and loot.
 *
 * Reached from the hub's explore section and from nowhere else — the bottom bar is four tabs
 * on purpose. See `TopLevelDestination`.
 */
@Serializable
data object QuestLogDestination

/** Body measurements (#109). The hub is the only way in, as it is for the quest log. */
@Serializable
data object BodyMeasurementsDestination

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
 * The four screens the hub drills into, registered outside the tab scaffold.
 *
 * Four registrations of one screen today — see [ProgressionDetailScreen] for why the
 * destinations ship with the hub while the screens behind them are still being built, and for
 * which issue replaces each. A registration is what the explore rows navigate to, so replacing
 * one is a change to the line below it and to nothing else.
 *
 * @param onBack a step back out. One lambda for all four, because it is one pop.
 */
fun NavGraphBuilder.progressionDetailGraph(onBack: () -> Unit) {
    composable<PersonalRecordsDestination> {
        ProgressionDetailScreen(detail = ProgressionDetail.RECORDS, onBack = onBack)
    }
    composable<ExerciseProgressionDestination> {
        ProgressionDetailScreen(detail = ProgressionDetail.EXERCISE_PROGRESSION, onBack = onBack)
    }
    composable<QuestLogDestination> {
        ProgressionDetailScreen(detail = ProgressionDetail.QUEST_LOG, onBack = onBack)
    }
    composable<BodyMeasurementsDestination> {
        ProgressionDetailScreen(detail = ProgressionDetail.MEASUREMENTS, onBack = onBack)
    }
}
