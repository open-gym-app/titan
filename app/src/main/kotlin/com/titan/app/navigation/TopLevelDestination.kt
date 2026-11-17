package com.titan.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.ui.graphics.vector.ImageVector
import com.titan.app.R
import com.titan.core.ui.component.titan.TitanIcons
import com.titan.feature.history.navigation.HistoryGraphDestination
import com.titan.feature.history.navigation.LibraryGraphDestination
import com.titan.feature.home.navigation.HomeGraphDestination
import com.titan.feature.progression.navigation.ProgressionGraphDestination
import com.titan.feature.titan.navigation.TitanGraphDestination
import kotlin.reflect.KClass

/**
 * The bottom bar's four tabs.
 *
 * **Four, not five.** The wireframes draw a five-tab bar including Map and More; Map has
 * no content until the Gym Map milestone and a tab that opens an empty screen is worse
 * than no tab, and "More" is a tab that means nothing — its one MVP inhabitant is
 * Settings, which is reachable from the gear in the top bar where a once-a-month
 * destination belongs. Map arrives as a fifth tab with the Gym Map milestone, when there
 * is something behind it.
 *
 * Library sits third, between Sessions and Stats, because the bar reads left to right as
 * the shape of a workout: what today holds, what has been done, what there is to do it with,
 * and what it added up to. It is a tab and not a push off Sessions because it is the only
 * way into 873 exercises and the only place a custom one is made — behind a back button at
 * the top of that list, the way out is a long scroll from wherever the user is reading.
 *
 * Each entry points at a feature's *graph* rather than a screen, which is what lets the bar
 * keep a tab looking selected while the user is two screens deep inside it.
 *
 * This enum is the one place that names all four graphs at once, and it can be: `:app`
 * is the composition root and already depends on every feature. No feature can see it.
 */
enum class TopLevelDestination(
    val graphDestination: Any,
    @param:StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        graphDestination = HomeGraphDestination,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    SESSIONS(
        graphDestination = HistoryGraphDestination,
        labelRes = R.string.nav_sessions,
        selectedIcon = Icons.Filled.FitnessCenter,
        unselectedIcon = Icons.Outlined.FitnessCenter,
    ),
    LIBRARY(
        graphDestination = LibraryGraphDestination,
        labelRes = R.string.nav_library,
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
        unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
    ),
    STATS(
        graphDestination = ProgressionGraphDestination,
        labelRes = R.string.nav_stats,
        selectedIcon = Icons.Filled.Insights,
        unselectedIcon = Icons.Outlined.Insights,
    ),

    /** The companion: last, after Stats. One icon for both states — it is a line drawing. */
    TITAN(
        graphDestination = TitanGraphDestination,
        labelRes = R.string.nav_titan,
        selectedIcon = TitanIcons.titan,
        unselectedIcon = TitanIcons.titan,
    ),
    ;

    /**
     * The route the bar matches against the current destination's hierarchy.
     *
     * Derived, not declared. As a second constructor parameter this was a copy of
     * [graphDestination] that nothing kept in step: `graphDestination = HistoryGraphDestination`
     * beside `graphRoute = ProgressionGraphDestination::class` compiles, and yields a tab that
     * navigates to Sessions while highlighting Stats. There is one fact here, so there is one
     * place to state it.
     */
    val graphRoute: KClass<*> get() = graphDestination::class
}
