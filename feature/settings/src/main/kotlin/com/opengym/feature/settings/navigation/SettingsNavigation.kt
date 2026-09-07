// Named for the convention every feature's navigation file follows (`XNavigation.kt`) rather
// than for the one destination it happens to declare today — the sibling files hold two and
// three, and a file that renamed itself as destinations were added would be worse.
@file:Suppress("MatchingDeclarationName")

package com.opengym.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.opengym.feature.settings.SettingsRoute
import com.opengym.feature.settings.backup.BackupRoute
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
 * Backup and restore, reached from the Data & privacy row of the same name.
 *
 * A second flat destination in this graph rather than a module of its own — #38 records the
 * decision: backup is reachable only from a settings row, shares the delete-all confirmation
 * vocabulary this module already owns, and a `:feature:backup` whose sole entry point is
 * another module's row would buy a `checkArchitecture` entry and a build file and nothing else.
 */
@Serializable
data object BackupDestination

/**
 * @param appVersion the running build's version name, from `:app`. A feature module's own
 *   `BuildConfig` carries the library's version, which is not the number a user is being asked
 *   to quote in a bug report — nor the one an export should stamp into its envelope.
 * @param onBack leave the current screen. Hoisted like every other navigation action: the
 *   `NavController` belongs to `:app`. It serves both destinations, since both of them are one
 *   pop from where the user came from.
 * @param onOpenBackup open [BackupDestination]. Hoisted even though it stays inside this
 *   module, for the reason `progressionDetailGraph` hoists its own intra-slice push: the
 *   controller is `:app`'s, and a feature that navigated itself would need one of its own.
 */
fun NavGraphBuilder.settingsGraph(
    appVersion: String,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    composable<SettingsDestination> {
        SettingsRoute(appVersion = appVersion, onBack = onBack, onOpenBackup = onOpenBackup)
    }
    composable<BackupDestination> {
        BackupRoute(appVersion = appVersion, onBack = onBack)
    }
}
