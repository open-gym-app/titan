// Named for the convention every feature's navigation file follows (`XNavigation.kt`) rather
// than for the one destination it happens to declare today — the sibling files hold two and
// three, and a file that renamed itself as destinations were added would be worse.
@file:Suppress("MatchingDeclarationName")

package com.titan.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.titan.core.domain.navigation.DeepLinks
import com.titan.feature.settings.SettingsRoute
import com.titan.feature.settings.backup.BackupRoute
import com.titan.feature.settings.healthconnect.HealthConnectRoute
import com.titan.feature.settings.importer.ImportRoute
import com.titan.feature.settings.licences.OpenSourceLicencesRoute
import com.titan.feature.settings.reminder.ReminderRoute
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
 * The import from another app (#252), reached from the Data & privacy row under backup, from
 * the last onboarding step, and from the import's own notifications (`titan://settings/import`).
 *
 * In this module for the reason [BackupDestination] is: it sits beside the backup it is the
 * opposite of, and a module of its own would buy a build file and nothing else. Onboarding, in
 * another feature, reaches it through `:app`, like everything that crosses a feature boundary.
 */
@Serializable
data object ImportDestination

/**
 * The workout reminder's schedule (#39), one push above settings.
 *
 * Its own destination rather than a dialog on the list, because it is three controls and one of
 * them opens a dialog of its own — see `ReminderScreen`. Outside the tab scaffold like the
 * settings list it is pushed from, and for the same reason: it is a place you leave to.
 */
@Serializable
data object ReminderDestination

/**
 * The runtime dependency graph and its licences (#208), one push above settings.
 *
 * Its own destination for the same reason [BackupDestination] is: reachable only from a
 * settings row, and a module of its own would buy nothing beyond a build file and a
 * `checkArchitecture` entry.
 */
@Serializable
data object OpenSourceLicencesDestination

/**
 * Health Connect (#54), one push above settings, from the Data & privacy row of the same name.
 *
 * In this module for the reason [BackupDestination] is: its only entry point is a settings row.
 */
@Serializable
data object HealthConnectDestination

/**
 * @param appVersion the running build's version name, from `:app`. A feature module's own
 *   `BuildConfig` carries the library's version, which is not the number a user is being asked
 *   to quote in a bug report — nor the one an export should stamp into its envelope.
 * @param onBack leave the current screen. Hoisted like every other navigation action: the
 *   `NavController` belongs to `:app`. It serves all six destinations, since every one of
 *   them is one pop from where the user came from.
 * @param onOpenBackup open [BackupDestination]. Hoisted even though it stays inside this
 *   module, for the reason `progressionDetailGraph` hoists its own intra-slice push: the
 *   controller is `:app`'s, and a feature that navigated itself would need one of its own.
 * @param onOpenImport open [ImportDestination]. Hoisted for the same reason as [onOpenBackup].
 * @param onOpenExerciseLibrary open the Library tab, where an import's new custom exercises
 *   are reviewed. Another feature's screen, so `:app`'s to reach.
 * @param onOpenReminders open [ReminderDestination]. Hoisted for the same reason as
 *   [onOpenBackup]: the controller is `:app`'s.
 * @param onOpenLicences open [OpenSourceLicencesDestination]. Hoisted for the same reason as
 *   [onOpenBackup]: the controller is `:app`'s.
 * @param onOpenHealthConnect open [HealthConnectDestination]. Hoisted for the same reason as
 *   [onOpenBackup]: the controller is `:app`'s.
 */
fun NavGraphBuilder.settingsGraph(
    appVersion: String,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    composable<SettingsDestination> {
        SettingsRoute(
            appVersion = appVersion,
            onBack = onBack,
            onOpenBackup = onOpenBackup,
            onOpenImport = onOpenImport,
            onOpenReminders = onOpenReminders,
            onOpenLicences = onOpenLicences,
            onOpenHealthConnect = onOpenHealthConnect,
        )
    }
    composable<BackupDestination> {
        BackupRoute(appVersion = appVersion, onBack = onBack)
    }
    composable<ImportDestination>(
        // The whole URI rather than a base path: the destination takes no arguments, and reads
        // the import's state from the worker rather than from the link.
        deepLinks = listOf(navDeepLink<ImportDestination>(basePath = DeepLinks.HISTORY_IMPORT)),
    ) {
        ImportRoute(onBack = onBack, onOpenExerciseLibrary = onOpenExerciseLibrary)
    }
    composable<ReminderDestination> {
        ReminderRoute(onBack = onBack)
    }
    composable<HealthConnectDestination> {
        HealthConnectRoute(onBack = onBack)
    }
    composable<OpenSourceLicencesDestination> {
        OpenSourceLicencesRoute(onBack = onBack)
    }
}
