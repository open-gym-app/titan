package com.opengym.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.ui.graphics.vector.ImageVector
import com.opengym.app.R
import com.opengym.feature.history.navigation.HistoryGraphDestination
import com.opengym.feature.home.navigation.HomeGraphDestination
import com.opengym.feature.progression.navigation.ProgressionGraphDestination
import kotlin.reflect.KClass

/**
 * The bottom bar's three tabs.
 *
 * **Three, not five.** The wireframes draw a five-tab bar including Map and More; Map has
 * no content until M8 and a tab that opens an empty screen is worse than no tab, and "More"
 * is a tab that means nothing — its one MVP inhabitant is Settings, which is reachable
 * from the gear in the top bar where a once-a-month destination belongs. Map arrives as a
 * fourth tab in M8, when there is something behind it.
 *
 * Each entry points at a feature's *graph* rather than a screen, which is what lets the bar
 * keep a tab looking selected while the user is two screens deep inside it.
 *
 * This enum is the one place that names all three features at once, and it can be: `:app`
 * is the composition root and already depends on every feature. No feature can see it.
 */
enum class TopLevelDestination(
    val graphDestination: Any,
    val graphRoute: KClass<*>,
    @param:StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        graphDestination = HomeGraphDestination,
        graphRoute = HomeGraphDestination::class,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    SESSIONS(
        graphDestination = HistoryGraphDestination,
        graphRoute = HistoryGraphDestination::class,
        labelRes = R.string.nav_sessions,
        selectedIcon = Icons.Filled.FitnessCenter,
        unselectedIcon = Icons.Outlined.FitnessCenter,
    ),
    STATS(
        graphDestination = ProgressionGraphDestination,
        graphRoute = ProgressionGraphDestination::class,
        labelRes = R.string.nav_stats,
        selectedIcon = Icons.Filled.Insights,
        unselectedIcon = Icons.Outlined.Insights,
    ),
}
