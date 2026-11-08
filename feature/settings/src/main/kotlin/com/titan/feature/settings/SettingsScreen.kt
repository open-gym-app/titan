package com.titan.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPermissionState
import com.titan.core.domain.notification.NotificationPreferences
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.ui.component.BadgeTone
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PreferenceGroup
import com.titan.core.ui.component.PreferenceInfoRow
import com.titan.core.ui.component.PreferenceRow
import com.titan.core.ui.component.PreferenceSoonRow
import com.titan.core.ui.component.PreferenceSwitchRow
import com.titan.core.ui.component.detail
import com.titan.core.ui.component.detailOrNull
import com.titan.core.ui.component.label
import com.titan.core.ui.format.formatAsClock
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.format.formatReminderSchedule
import com.titan.core.ui.format.formatWeight
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.component.BarWeightDialog
import com.titan.feature.settings.component.ChoiceDialog
import com.titan.feature.settings.component.DeleteAllDataDialog
import com.titan.feature.settings.component.NotificationPermissionEffects
import com.titan.feature.settings.component.RestoreTemplatesDialog
import com.titan.feature.settings.component.SettingsChoice
import com.titan.feature.settings.component.TrainingGoalDialog
import com.titan.feature.settings.component.startAppNotificationSettings
import kotlinx.coroutines.flow.filterNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where the source lives. Shown in About, and the only outbound link that is live in v1. */
private const val GITHUB_URL = "https://github.com/open-gym-app/titan"

/**
 * The rest durations the picker offers.
 *
 * A list, not a wheel or a number field: a rest is chosen once and is one of a handful of
 * values in practice. 30 seconds is an accessory superset, 180 is the default and what a
 * working set of a compound lift needs, 300 is what a heavy squat or a max single needs — the
 * range covers the whole of what a gym does without asking anyone to scroll a picker to it.
 */
private val REST_DURATIONS: List<Duration> =
    listOf(30, 45, 60, 90, 120, 150, 180, 240, 300).map { it.seconds }

/**
 * Settings, wired to its ViewModel.
 *
 * @param appVersion the running build's version name. It comes from `:app` rather than from
 *   this module's own `BuildConfig`, which carries the *library's* version and would read
 *   `1.0` on every build forever.
 */
@Composable
fun SettingsRoute(
    appVersion: String,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    // Everything platform-shaped about the notification permission lives in the route rather
    // than in `SettingsScreen`, which stays a function of its state: only something attached
    // to an Activity can raise a permission prompt or start an intent, and a stateless screen
    // that could do either would need one to be tested. The reminder screen does the same
    // three things, which is why they are one composable — see [NotificationPermissionEffects].
    NotificationPermissionEffects(
        request = state.notificationPermissionRequest,
        onRequestShown = { viewModel.onEvent(SettingsEvent.Notification.PermissionRequestShown) },
        onRecheck = { viewModel.onEvent(SettingsEvent.Notification.PermissionRechecked) },
    )

    SettingsScreen(
        state = state,
        appVersion = appVersion,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onOpenBackup = onOpenBackup,
        // Not hoisted to `:app`: opening a URL is not navigation, it leaves the app entirely,
        // and `LocalUriHandler` is already the platform seam a test can replace.
        onOpenLink = uriHandler::openUri,
        onOpenNotificationSettings = { context.startAppNotificationSettings() },
        onOpenReminders = onOpenReminders,
        onOpenLicences = onOpenLicences,
        modifier = modifier,
    )
}

/**
 * Every setting the app has, and a row for every one it is going to have.
 *
 * The full information architecture ships now with the not-yet-built rows visibly disabled —
 * the decision recorded in #M4-23 — so later milestones fill a row in rather than moving the
 * furniture under people who have learned where things are.
 *
 * The screen is stateless: it renders [state] and reports [SettingsEvent]s. It reads no
 * preference of its own, which is what makes the theme and unit changes here apply everywhere
 * else — `:app` collects the same store at the root and hands it to `TitanTheme`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.text()

    // The message is marked shown *before* the snackbar is, which is what makes
    // `SettingsMessage`'s "cannot be shown twice by a rotation" true: `showSnackbar` suspends
    // for the whole four seconds it is up, so clearing afterwards leaves the message sitting
    // in the (surviving) ViewModel for a recomposed screen to show a second time.
    //
    // Keyed on `Unit` and read through `rememberUpdatedState` rather than keyed on the
    // message, because clearing it immediately would otherwise re-key this effect and cancel
    // the `showSnackbar` it is in the middle of — dismissing the snackbar on the same frame
    // it appeared.
    val currentMessage by rememberUpdatedState(message)
    LaunchedEffect(Unit) {
        snapshotFlow { currentMessage }
            .filterNotNull()
            .collect { text ->
                onEvent(SettingsEvent.MessageShown)
                snackbarHostState.showSnackbar(text)
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
        ) {
            AppearanceGroup(state.preferences, onEvent)
            WorkoutGroup(state.preferences, state.missingDefaultTemplates, onEvent)
            DataGroup(onEvent = onEvent, onOpenBackup = onOpenBackup)
            NotificationsGroup(
                preferences = state.preferences,
                permission = state.notificationPermission,
                onEvent = onEvent,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onOpenReminders = onOpenReminders,
            )
            AboutGroup(appVersion = appVersion, onOpenLink = onOpenLink, onOpenLicences = onOpenLicences)
            Footer()
        }
    }

    SettingsDialogs(state = state, onEvent = onEvent)
}

@Composable
private fun AppearanceGroup(
    preferences: UserPreferences,
    onEvent: (SettingsEvent) -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.settings_group_appearance)) {
        PreferenceRow(
            label = stringResource(R.string.settings_theme),
            value = preferences.theme.label(),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.THEME)) },
        )
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.settings_units),
            value = preferences.unitSystem.label(),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.UNITS)) },
        )
        HairlineDivider()
        // Not a disabled row and not a picker: Android already has this setting, it already
        // works here, and a second copy of it inside the app is a place for the two to
        // disagree. The row exists to say so, which is why it states the value it follows.
        PreferenceInfoRow(
            label = stringResource(R.string.settings_font_scale),
            value = stringResource(R.string.settings_font_scale_value),
            supporting = stringResource(R.string.settings_font_scale_detail),
        )
    }
}

@Composable
private fun WorkoutGroup(
    preferences: UserPreferences,
    missingDefaultTemplates: List<String>,
    onEvent: (SettingsEvent) -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.settings_group_workout)) {
        PreferenceRow(
            label = stringResource(R.string.settings_rest_timer),
            value = preferences.defaultRestDuration.formatAsClock(),
            supporting = stringResource(R.string.settings_rest_timer_detail),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.REST_DURATION)) },
        )
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.settings_bar_weight),
            value = formatWeight(preferences.barWeight, preferences.unitSystem),
            supporting = stringResource(R.string.settings_bar_weight_detail),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.BAR_WEIGHT)) },
        )
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.settings_training_goal),
            value = preferences.trainingGoal.label(),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.TRAINING_GOAL)) },
        )
        HairlineDivider()
        // Beside the goal because the two are one decision to the next-load engine: the goal
        // picks the rep range, the level picks how fast the load moves through it (#M7-15).
        PreferenceRow(
            label = stringResource(R.string.settings_fitness_level),
            value = preferences.fitnessLevel.label(),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.FITNESS_LEVEL)) },
        )
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.settings_one_rep_max_formula),
            value = preferences.oneRepMaxFormula.label(),
            supporting = stringResource(R.string.settings_one_rep_max_formula_detail),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.ONE_REP_MAX_FORMULA)) },
        )
        HairlineDivider()
        // With the training settings rather than on the quest log, because it is a plan the user
        // makes about their training: the streak is only what keeps count of it.
        PreferenceRow(
            label = stringResource(R.string.settings_weekly_goal),
            // The bare count: the label and the line under it already say what it counts, and
            // a phrase in the value column squeezes the label into a column of single words.
            value = formatCount(preferences.weeklyWorkoutGoal),
            supporting = stringResource(R.string.settings_weekly_goal_detail),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.WEEKLY_GOAL)) },
        )
        HairlineDivider()
        // The only switch outside the notifications group, and the only row in this one that is
        // about what a *screen* offers rather than about how a number is worked out. It is here
        // rather than under Appearance because what it hides is training advice, not chrome.
        PreferenceSwitchRow(
            label = stringResource(R.string.settings_warmup_suggestions),
            supporting = stringResource(R.string.settings_warmup_suggestions_detail),
            checked = preferences.warmupSuggestions,
            onCheckedChange = { onEvent(SettingsEvent.WarmupSuggestionsToggled(it)) },
        )
        HairlineDivider()
        // Last in the group, after the settings rather than among them: it is an action on the
        // template list, not a value, and putting it beside the goal would split the goal from
        // the level it is one decision with. Disabled rather than hidden when nothing is
        // missing, so the row is where the user learned it was and says why it does nothing.
        PreferenceRow(
            label = stringResource(R.string.settings_restore_templates),
            supporting = if (missingDefaultTemplates.isEmpty()) {
                stringResource(R.string.settings_restore_templates_none_missing)
            } else {
                pluralStringResource(
                    R.plurals.settings_restore_templates_detail,
                    missingDefaultTemplates.size,
                    missingDefaultTemplates.size,
                )
            },
            enabled = missingDefaultTemplates.isNotEmpty(),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.RESTORE_TEMPLATES)) },
        )
    }
}

@Composable
private fun DataGroup(
    onEvent: (SettingsEvent) -> Unit,
    onOpenBackup: () -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.settings_group_data)) {
        // Was two disabled rows — "Backup & restore" and "Export JSON" — until #38. They are
        // one row now rather than two, because they were always one screen: an export is how
        // you take a backup, and a row promising it separately would open the same place.
        PreferenceRow(
            label = stringResource(R.string.settings_backup),
            supporting = stringResource(R.string.settings_backup_detail),
            onClick = onOpenBackup,
        )
        HairlineDivider()
        PreferenceSoonRow(label = stringResource(R.string.settings_health_connect))
        HairlineDivider()
        // The one row in this group that is real, and the only destructive action in the app.
        PreferenceRow(
            label = stringResource(R.string.settings_delete_all),
            supporting = stringResource(R.string.settings_delete_all_detail),
            destructive = true,
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.DELETE_ALL)) },
        )
    }
}

/**
 * The four notification switches, and whatever the system has to say about them.
 *
 * A switch here records what the user *wants*; whether anything arrives is the permission's
 * business, and the two are drawn as two things on purpose. A denied permission does not
 * disable the switches — the setting is still theirs to change, and greying it out would say
 * otherwise while hiding what they came here to set — it flags the ones that are on and not
 * working, and adds the one row that can still fix it.
 */
@Composable
private fun NotificationsGroup(
    preferences: UserPreferences,
    permission: NotificationPermissionState,
    onEvent: (SettingsEvent) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenReminders: () -> Unit,
) {
    val notifications = preferences.notifications

    PreferenceGroup(title = stringResource(R.string.settings_group_notifications)) {
        when (permission) {
            NotificationPermissionState.GRANTED -> Unit

            // Refused, or switched off in system settings. The prompt will not come back, so
            // the only thing left to offer is the way to the screen that can undo it.
            NotificationPermissionState.DENIED -> {
                PreferenceRow(
                    label = stringResource(R.string.settings_notifications_blocked),
                    supporting = stringResource(R.string.settings_notifications_blocked_detail),
                    external = true,
                    onClick = onOpenNotificationSettings,
                )
                HairlineDivider()
            }

            // Never asked — which a fresh install is, with the rest timer already on. The
            // switches ask on the way past; this row is for the user whose setting is already
            // enabled and so has nothing left to enable.
            NotificationPermissionState.NOT_REQUESTED -> {
                PreferenceRow(
                    label = stringResource(R.string.settings_notifications_allow),
                    supporting = stringResource(R.string.settings_notifications_allow_detail),
                    onClick = { onEvent(SettingsEvent.Notification.PermissionRequested) },
                )
                HairlineDivider()
            }
        }

        // The reminders row is a way in rather than a switch, and it is the only one of the
        // four that is: the other three are a notification the app decides to post, where this
        // one is an appointment the user sets. Its switch, its days and its time live on the
        // screen behind it — see `ReminderScreen` — and the value column reads the schedule
        // back the way the wireframe draws it: `MWF · 18:00`.
        PreferenceRow(
            label = stringResource(NotificationKind.REMINDERS.labelRes()),
            value = when {
                NotificationKind.REMINDERS in notifications -> formatReminderSchedule(preferences.reminders)

                // The switch, not the schedule. A row reading `MWF · 18:00` for a reminder
                // that is turned off states a time nothing is going to happen at.
                else -> stringResource(R.string.settings_reminders_off)
            },
            supporting = stringResource(NotificationKind.REMINDERS.detailRes()),
            onClick = onOpenReminders,
        )
        HairlineDivider()

        NotificationKind.entries.filterNot { it == NotificationKind.REMINDERS }.forEach { kind ->
            PreferenceSwitchRow(
                label = stringResource(kind.labelRes()),
                supporting = stringResource(kind.detailRes()),
                checked = kind in notifications,
                // Only a switch that is *on* can be blocked. Flagging an off one would be
                // telling the user that something they turned off is not being delivered.
                badge = stringResource(R.string.settings_notifications_badge_blocked)
                    .takeIf { kind in notifications && permission == NotificationPermissionState.DENIED },
                badgeTone = BadgeTone.CRITICAL,
                onCheckedChange = { onEvent(SettingsEvent.Notification.Toggled(kind, it)) },
            )
            HairlineDivider()
        }

        // The one row in this group still waiting on a milestone: the marketplace is M8 (#52).
        PreferenceSoonRow(label = stringResource(R.string.settings_marketplace))
    }
}

@Composable
private fun AboutGroup(
    appVersion: String,
    onOpenLink: (String) -> Unit,
    onOpenLicences: () -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.settings_group_about)) {
        PreferenceInfoRow(label = stringResource(R.string.settings_version), value = appVersion)
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.settings_github),
            supporting = stringResource(R.string.settings_github_detail),
            external = true,
            onClick = { onOpenLink(GITHUB_URL) },
        )
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.settings_licences),
            supporting = stringResource(R.string.settings_licences_detail),
            onClick = onOpenLicences,
        )
        HairlineDivider()
        // Disabled until there is somewhere to send people. A row promising a donation link
        // that opens nothing is worse than one that says it is not ready.
        PreferenceSoonRow(label = stringResource(R.string.settings_donate))
    }
}

/**
 * The closing line: what this app does not do.
 *
 * Worth a permanent place at the bottom of settings rather than a paragraph in a privacy
 * policy nobody opens. It is the reason a good part of the audience is here, and settings is
 * where somebody goes to find out what an app is up to.
 */
@Composable
private fun Footer() {
    Text(
        text = stringResource(R.string.settings_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsDialogs(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val dismiss = { onEvent(SettingsEvent.DialogDismissed) }

    when (state.dialog) {
        null -> Unit

        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            choices = ThemePreference.entries.map { theme ->
                SettingsChoice(
                    value = theme,
                    label = theme.label(),
                    detail = theme.detailOrNull(),
                )
            },
            selected = state.preferences.theme,
            onSelect = { onEvent(SettingsEvent.ThemeSelected(it)) },
            onDismiss = dismiss,
        )

        SettingsDialog.UNITS -> ChoiceDialog(
            title = stringResource(R.string.settings_units),
            choices = UnitSystem.entries.map { units ->
                SettingsChoice(
                    value = units,
                    label = units.label(),
                    detail = units.detail(),
                )
            },
            selected = state.preferences.unitSystem,
            onSelect = { onEvent(SettingsEvent.UnitSystemSelected(it)) },
            onDismiss = dismiss,
        )

        SettingsDialog.REST_DURATION -> ChoiceDialog(
            title = stringResource(R.string.settings_rest_timer),
            choices = REST_DURATIONS.map { SettingsChoice(it, it.formatAsClock()) },
            selected = state.preferences.defaultRestDuration,
            onSelect = { onEvent(SettingsEvent.RestDurationSelected(it)) },
            onDismiss = dismiss,
        )

        SettingsDialog.BAR_WEIGHT -> BarWeightDialog(
            barWeight = state.preferences.barWeight,
            unitSystem = state.preferences.unitSystem,
            onConfirm = { onEvent(SettingsEvent.BarWeightSelected(it)) },
            onDismiss = dismiss,
        )

        SettingsDialog.TRAINING_GOAL -> TrainingGoalDialog(
            goal = state.preferences.trainingGoal,
            customRepRange = state.preferences.customRepRange,
            // The step the engine would use: the stored one, or the rack's own — see
            // `NextLoadEngine`, which derives it the same way. Seeding the field with it means
            // Save without an edit writes down what was already in effect.
            customIncrement = state.preferences.customIncrement
                ?: state.preferences.plates.loadStep
                ?: checkNotNull(PlateInventory.defaultFor(state.preferences.unitSystem).loadStep),
            unitSystem = state.preferences.unitSystem,
            onConfirm = { goal, range, increment ->
                onEvent(SettingsEvent.TrainingGoalSelected(goal, customRepRange = range, customIncrement = increment))
            },
            onDismiss = dismiss,
        )

        SettingsDialog.FITNESS_LEVEL -> ChoiceDialog(
            title = stringResource(R.string.settings_fitness_level),
            choices = FitnessLevel.entries.map { level ->
                SettingsChoice(
                    value = level,
                    label = level.label(),
                    detail = level.detail(),
                )
            },
            selected = state.preferences.fitnessLevel,
            onSelect = { onEvent(SettingsEvent.FitnessLevelSelected(it)) },
            onDismiss = dismiss,
        )

        SettingsDialog.ONE_REP_MAX_FORMULA -> ChoiceDialog(
            title = stringResource(R.string.settings_one_rep_max_formula),
            choices = OneRepMaxFormula.entries.map { formula ->
                SettingsChoice(
                    value = formula,
                    label = formula.label(),
                    detail = formula.detail(),
                )
            },
            selected = state.preferences.oneRepMaxFormula,
            onSelect = { onEvent(SettingsEvent.OneRepMaxFormulaSelected(it)) },
            onDismiss = dismiss,
        )

        SettingsDialog.WEEKLY_GOAL -> ChoiceDialog(
            title = stringResource(R.string.settings_weekly_goal),
            choices = StreakCalculator.WEEKLY_GOAL_RANGE.map { goal ->
                SettingsChoice(
                    value = goal,
                    label = pluralStringResource(R.plurals.settings_weekly_goal_value, goal, goal),
                )
            },
            selected = state.preferences.weeklyWorkoutGoal,
            onSelect = { onEvent(SettingsEvent.WeeklyGoalSelected(it)) },
            onDismiss = dismiss,
        )

        SettingsDialog.RESTORE_TEMPLATES -> RestoreTemplatesDialog(
            missing = state.missingDefaultTemplates,
            onConfirm = { onEvent(SettingsEvent.RestoreDefaultTemplatesConfirmed) },
            onDismiss = dismiss,
        )

        SettingsDialog.DELETE_ALL -> DeleteAllDataDialog(
            footprint = state.footprint,
            isDeleting = state.isDeleting,
            onConfirm = { onEvent(SettingsEvent.DeleteAllConfirmed) },
            onDismiss = dismiss,
        )
    }
}

/**
 * Settings' own top bar.
 *
 * The shell hides its chrome outside the tabs (see `TitanApp`), so this screen draws its
 * own — the same shape, with the gear replaced by the way back. The hairline under it is what
 * every bar in the wireframes has instead of elevation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// --- previews -------------------------------------------------------------------------

/** Tall enough to capture the whole screen; a preview clips at the device height otherwise. */
private const val FULL_SCREEN_HEIGHT_DP = 1500

@Preview(name = "Settings", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SettingsScreenPreview() {
    TitanTheme {
        SettingsScreen(
            state = SettingsUiState(),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenBackup = {},
            onOpenLink = {},
            onOpenNotificationSettings = {},
            onOpenReminders = {},
            onOpenLicences = {},
        )
    }
}

/**
 * Every label at twice its size — the bar #40 holds every screen to, on the pattern Home set.
 *
 * Recorded rather than argued about: the reference image is the evidence, it is a screenshot
 * test like any other, and a change that starts clipping or overlapping something at 2x fails
 * the build. Robolectric's font metrics are not a phone's, so the device walk in
 * docs/ACCESSIBILITY.md is still the result; this is what keeps it from rotting.
 */
@Preview(
    name = "Settings - twice the font",
    showBackground = true,
    heightDp = FULL_SCREEN_HEIGHT_DP * 2,
    fontScale = 2f,
)
@Composable
fun SettingsScreenLargeFontPreview() {
    TitanTheme {
        SettingsScreen(
            state = SettingsUiState(),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenBackup = {},
            onOpenLink = {},
            onOpenNotificationSettings = {},
            onOpenReminders = {},
            onOpenLicences = {},
        )
    }
}

@Preview(name = "Settings - dark, imperial", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SettingsScreenDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, unitSystem = UnitSystem.IMPERIAL) {
        SettingsScreen(
            state = SettingsUiState(
                preferences = UserPreferences(
                    theme = ThemePreference.DARK,
                    unitSystem = UnitSystem.IMPERIAL,
                    defaultRestDuration = 180.seconds,
                    barWeight = 20_411_657,
                    trainingGoal = TrainingGoal.HYPERTROPHY,
                ),
                // The restore row enabled, which the light previews — a fresh install, nothing
                // deleted — never draw.
                missingDefaultTemplates = listOf("Legs", "Arms"),
            ),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenBackup = {},
            onOpenLink = {},
            onOpenNotificationSettings = {},
            onOpenReminders = {},
            onOpenLicences = {},
        )
    }
}

@Preview(name = "Settings - delete confirmation", showBackground = true)
@Composable
fun SettingsScreenDeleteDialogPreview() {
    TitanTheme {
        SettingsScreen(
            state = SettingsUiState(
                dialog = SettingsDialog.DELETE_ALL,
                footprint = DataFootprint(
                    sessions = 184,
                    sets = 2_140,
                    templates = 6,
                    personalRecords = 23,
                    customExercises = 2,
                    bodyMeasurements = 61,
                ),
            ),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenBackup = {},
            onOpenLink = {},
            onOpenNotificationSettings = {},
            onOpenReminders = {},
            onOpenLicences = {},
        )
    }
}

@Preview(name = "Settings - notifications blocked", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SettingsScreenNotificationsBlockedPreview() {
    TitanTheme {
        SettingsScreen(
            state = SettingsUiState(
                preferences = UserPreferences(
                    notifications = NotificationPreferences(
                        enabled = setOf(NotificationKind.REST_TIMER, NotificationKind.REMINDERS),
                    ),
                ),
                notificationPermission = NotificationPermissionState.DENIED,
            ),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenBackup = {},
            onOpenLink = {},
            onOpenNotificationSettings = {},
            onOpenReminders = {},
            onOpenLicences = {},
        )
    }
}

@Preview(name = "Settings - theme picker", showBackground = true)
@Composable
fun SettingsScreenThemeDialogPreview() {
    TitanTheme {
        SettingsScreen(
            state = SettingsUiState(dialog = SettingsDialog.THEME),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenBackup = {},
            onOpenLink = {},
            onOpenNotificationSettings = {},
            onOpenReminders = {},
            onOpenLicences = {},
        )
    }
}

@Preview(name = "Settings - restore default templates", showBackground = true)
@Composable
fun SettingsScreenRestoreTemplatesDialogPreview() {
    TitanTheme {
        SettingsScreen(
            state = SettingsUiState(
                dialog = SettingsDialog.RESTORE_TEMPLATES,
                missingDefaultTemplates = listOf("Legs", "Push", "HIIT"),
            ),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenBackup = {},
            onOpenLink = {},
            onOpenNotificationSettings = {},
            onOpenReminders = {},
            onOpenLicences = {},
        )
    }
}
