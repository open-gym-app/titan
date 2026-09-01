package com.opengym.feature.progression.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.opengym.feature.progression.detail.ProgressionDetail
import com.opengym.feature.progression.detail.ProgressionDetailScreen
import com.opengym.feature.progression.exercise.ExerciseProgressionRoute
import com.opengym.feature.progression.records.PersonalRecordsRoute
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
 * Three of the four are still one placeholder screen — see [ProgressionDetailScreen] for why
 * the destinations ship with the hub while the screens behind them are being built, and for
 * which issue replaces each. A registration is what the explore rows navigate to, so replacing
 * one is a change to the line below it and to nothing else; #102 is the first to be replaced,
 * and its entry is what the other three grow into.
 *
 * @param onBack a step back out. One lambda for all four, because it is one pop.
 * @param onOpenExerciseProgression a record's exercise, opened on its own drill-down. Hoisted
 *   rather than navigated here: both destinations live in this graph, but which one a record row
 *   opens is a *routing* decision, and `:app` is where every other one of those is made.
 */
fun NavGraphBuilder.progressionDetailGraph(
    onBack: () -> Unit,
    onOpenExerciseProgression: (Long) -> Unit,
) {
    composable<PersonalRecordsDestination> {
        PersonalRecordsRoute(onBack = onBack, onOpenExercise = onOpenExerciseProgression)
    }
    composable<ExerciseProgressionDestination> {
        ExerciseProgressionRoute(onBack = onBack)
    }
    composable<QuestLogDestination> {
        ProgressionDetailScreen(detail = ProgressionDetail.QUEST_LOG, onBack = onBack)
    }
    composable<BodyMeasurementsDestination> {
        ProgressionDetailScreen(detail = ProgressionDetail.MEASUREMENTS, onBack = onBack)
    }
}
