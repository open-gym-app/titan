// Named for the convention every feature's navigation file follows (`XNavigation.kt`) rather
// than for the one destination it happens to declare today — the sibling files hold two and
// three, and a file that renamed itself as destinations were added would be worse.
@file:Suppress("MatchingDeclarationName")

package com.opengym.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.opengym.feature.settings.SettingsRoute
import kotlinx.serialization.Serializable

/**
 * Settings, reached from the gear in the tab scaffold's top app bar.
 *
 * A flat destination rather than a nested graph, unlike a tab: the tabs are graphs because the
 * bottom bar asks whether a tab's route is anywhere in the current destination's hierarchy,
 * and settings has no tab to keep selected. It sits *outside* the tab scaffold entirely —
 * settings is a place you leave to, and the bars are hidden while you are there.
 */
@Serializable
data object SettingsDestination

/**
 * @param appVersion the running build's version name, from `:app`. A feature module's own
 *   `BuildConfig` carries the library's version, which is not the number a user is being asked
 *   to quote in a bug report.
 * @param onBack leave settings. Hoisted like every other navigation action: the `NavController`
 *   belongs to `:app`.
 */
fun NavGraphBuilder.settingsGraph(
    appVersion: String,
    onBack: () -> Unit,
) {
    composable<SettingsDestination> {
        SettingsRoute(appVersion = appVersion, onBack = onBack)
    }
}
