package com.opengym.feature.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.format.formatAsClock
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.component.BarWeightDialog
import com.opengym.feature.settings.component.ChoiceDialog
import com.opengym.feature.settings.component.DeleteAllDataDialog
import com.opengym.feature.settings.component.SettingsChoice
import com.opengym.feature.settings.component.SettingsGroup
import com.opengym.feature.settings.component.SettingsInfoRow
import com.opengym.feature.settings.component.SettingsRow
import com.opengym.feature.settings.component.SettingsSoonRow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where the source lives. Shown in About, and the only outbound link that is live in v1. */
private const val GITHUB_URL = "https://github.com/open-gym-app/open-gym"

/**
 * The rest durations the picker offers.
 *
 * A list, not a wheel or a number field: a rest is chosen once and is one of a handful of
 * values in practice. 30 seconds is an accessory superset, 90 is the default, 180 and 300 are
 * what a heavy squat or a max single actually needs — the range covers the whole of what a
 * gym does without asking anyone to scroll a picker to it.
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
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    SettingsScreen(
        state = state,
        appVersion = appVersion,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        // Not hoisted to `:app`: opening a URL is not navigation, it leaves the app entirely,
        // and `LocalUriHandler` is already the platform seam a test can replace.
        onOpenLink = uriHandler::openUri,
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
 * else — `:app` collects the same store at the root and hands it to `OpenGymTheme`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it.textRes()) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onEvent(SettingsEvent.MessageShown)
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
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
        ) {
            AppearanceGroup(state.preferences, onEvent)
            WorkoutGroup(state.preferences, onEvent)
            DataGroup(onEvent)
            NotificationsGroup()
            AboutGroup(appVersion = appVersion, onOpenLink = onOpenLink)
            Footer()
        }
    }

    SettingsDialogs(state = state, onEvent = onEvent)
}

@Composable
private fun AppearanceGroup(preferences: UserPreferences, onEvent: (SettingsEvent) -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_group_appearance)) {
        SettingsRow(
            label = stringResource(R.string.settings_theme),
            value = stringResource(preferences.theme.labelRes()),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.THEME)) },
        )
        HairlineDivider()
        SettingsRow(
            label = stringResource(R.string.settings_units),
            value = stringResource(preferences.unitSystem.labelRes()),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.UNITS)) },
        )
        HairlineDivider()
        // Not a disabled row and not a picker: Android already has this setting, it already
        // works here, and a second copy of it inside the app is a place for the two to
        // disagree. The row exists to say so, which is why it states the value it follows.
        SettingsInfoRow(
            label = stringResource(R.string.settings_font_scale),
            value = stringResource(R.string.settings_font_scale_value),
            supporting = stringResource(R.string.settings_font_scale_detail),
        )
    }
}

@Composable
private fun WorkoutGroup(preferences: UserPreferences, onEvent: (SettingsEvent) -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_group_workout)) {
        SettingsRow(
            label = stringResource(R.string.settings_rest_timer),
            value = preferences.defaultRestDuration.formatAsClock(),
            supporting = stringResource(R.string.settings_rest_timer_detail),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.REST_DURATION)) },
        )
        HairlineDivider()
        SettingsRow(
            label = stringResource(R.string.settings_bar_weight),
            value = formatWeight(preferences.barWeight, preferences.unitSystem),
            supporting = stringResource(R.string.settings_bar_weight_detail),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.BAR_WEIGHT)) },
        )
        HairlineDivider()
        SettingsRow(
            label = stringResource(R.string.settings_training_goal),
            value = stringResource(preferences.trainingGoal.labelRes()),
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.TRAINING_GOAL)) },
        )
    }
}

@Composable
private fun DataGroup(onEvent: (SettingsEvent) -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_group_data)) {
        SettingsSoonRow(
            label = stringResource(R.string.settings_backup),
            supporting = stringResource(R.string.settings_soon_detail),
        )
        HairlineDivider()
        SettingsSoonRow(label = stringResource(R.string.settings_export))
        HairlineDivider()
        SettingsSoonRow(label = stringResource(R.string.settings_health_connect))
        HairlineDivider()
        // The one row in this group that is real, and the only destructive action in the app.
        SettingsRow(
            label = stringResource(R.string.settings_delete_all),
            supporting = stringResource(R.string.settings_delete_all_detail),
            destructive = true,
            onClick = { onEvent(SettingsEvent.DialogRequested(SettingsDialog.DELETE_ALL)) },
        )
    }
}

@Composable
private fun NotificationsGroup() {
    SettingsGroup(title = stringResource(R.string.settings_group_notifications)) {
        SettingsSoonRow(label = stringResource(R.string.settings_reminders))
        HairlineDivider()
        SettingsSoonRow(label = stringResource(R.string.settings_streak_protection))
        HairlineDivider()
        SettingsSoonRow(label = stringResource(R.string.settings_marketplace))
    }
}

@Composable
private fun AboutGroup(appVersion: String, onOpenLink: (String) -> Unit) {
    SettingsGroup(title = stringResource(R.string.settings_group_about)) {
        SettingsInfoRow(label = stringResource(R.string.settings_version), value = appVersion)
        HairlineDivider()
        SettingsRow(
            label = stringResource(R.string.settings_github),
            supporting = stringResource(R.string.settings_github_detail),
            external = true,
            onClick = { onOpenLink(GITHUB_URL) },
        )
        HairlineDivider()
        // Disabled until there is somewhere to send people. A row promising a donation link
        // that opens nothing is worse than one that says it is not ready.
        SettingsSoonRow(label = stringResource(R.string.settings_donate))
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
private fun SettingsDialogs(state: SettingsUiState, onEvent: (SettingsEvent) -> Unit) {
    val dismiss = { onEvent(SettingsEvent.DialogDismissed) }

    when (state.dialog) {
        null -> Unit

        SettingsDialog.THEME -> ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            choices = ThemePreference.entries.map { theme ->
                SettingsChoice(
                    value = theme,
                    label = stringResource(theme.labelRes()),
                    detail = theme.detailRes()?.let { stringResource(it) },
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
                    label = stringResource(units.labelRes()),
                    detail = stringResource(units.detailRes()),
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

        SettingsDialog.TRAINING_GOAL -> ChoiceDialog(
            title = stringResource(R.string.settings_training_goal),
            choices = TrainingGoal.entries.map { goal ->
                SettingsChoice(
                    value = goal,
                    label = stringResource(goal.labelRes()),
                    detail = stringResource(goal.detailRes()),
                )
            },
            selected = state.preferences.trainingGoal,
            onSelect = { onEvent(SettingsEvent.TrainingGoalSelected(it)) },
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
 * The shell hides its chrome outside the tabs (see `OpenGymApp`), so this screen draws its
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
    OpenGymTheme {
        SettingsScreen(
            state = SettingsUiState(),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Settings - dark, imperial", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SettingsScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK, unitSystem = UnitSystem.IMPERIAL) {
        SettingsScreen(
            state = SettingsUiState(
                preferences = UserPreferences(
                    theme = ThemePreference.DARK,
                    unitSystem = UnitSystem.IMPERIAL,
                    defaultRestDuration = 180.seconds,
                    barWeight = 20_411_657,
                    trainingGoal = TrainingGoal.HYPERTROPHY,
                ),
            ),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Settings - delete confirmation", showBackground = true)
@Composable
fun SettingsScreenDeleteDialogPreview() {
    OpenGymTheme {
        SettingsScreen(
            state = SettingsUiState(
                dialog = SettingsDialog.DELETE_ALL,
                footprint = DataFootprint(
                    sessions = 184,
                    sets = 2_140,
                    templates = 6,
                    personalRecords = 23,
                    customExercises = 2,
                    bodyWeightLogs = 61,
                ),
            ),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Settings - theme picker", showBackground = true)
@Composable
fun SettingsScreenThemeDialogPreview() {
    OpenGymTheme {
        SettingsScreen(
            state = SettingsUiState(dialog = SettingsDialog.THEME),
            appVersion = "1.0",
            onEvent = {},
            onBack = {},
            onOpenLink = {},
        )
    }
}
