package com.opengym.feature.progression.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.opengym.feature.progression.StatsScreen
import kotlinx.serialization.Serializable

/** The Stats tab's graph. Navigating here lands on [StatsDestination]. */
@Serializable
data object ProgressionGraphDestination

/** The stats overview (#32). */
@Serializable
data object StatsDestination

/**
 * The Stats tab.
 *
 * It takes no navigation actions yet. The drill-downs the overview links to — exercise
 * progression (#102), the PR list (#103), the quest log (#104) and body measurements
 * (#109) — are all M5, and each will land here as its own destination inside this graph
 * rather than as a lambda, because they belong to this feature.
 */
fun NavGraphBuilder.progressionGraph() {
    navigation<ProgressionGraphDestination>(startDestination = StatsDestination) {
        composable<StatsDestination> {
            StatsScreen()
        }
    }
}
