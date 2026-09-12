package com.opengym.feature.settings.reminder

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.notification.NotificationPermissionState
import com.opengym.core.domain.notification.ReminderPreferences
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.PreferenceGroup
import com.opengym.core.ui.component.PreferenceRow
import com.opengym.core.ui.component.PreferenceSwitchRow
import com.opengym.core.ui.component.TimePickerDialog
import com.opengym.core.ui.component.WeekdayPicker
import com.opengym.core.ui.format.formatReminderDays
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.format.formatTimeOfDay
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The workout reminder: the switch, the days, and the time.
 *
 * ### Why it is a screen rather than a dialog on the settings list
 *
 * The other settings rows open a picker because each of them is *one* value. This is three —
 * an on/off, a set of seven, and a time — and a dialog holding all three would have to open a
 * second dialog for the clock face. It is also the one setting the user will come back to
 * check rather than set once, which is what the header is for: the whole point of the screen
 * is being able to see at a glance that the next one is on Monday.
 *
 * ### The header states the outcome, not the settings
 *
 * The two cards below already say which days and what time. The header answers the question
 * somebody actually opened the screen with — *is this going to go off, and when* — which is a
 * different question with four different answers: it is off, no day is chosen, the system is
 * blocking it, or it is due at a moment that can be named. Only the last of those is drawn as
 * a date; the rest are a sentence, because a date beside "reminders are off" would be the
 * screen contradicting itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(
    state: ReminderUiState,
    onEvent: (ReminderEvent) -> Unit,
    onBack: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.settings_save_failed)

    // Marked shown before the snackbar is, for the reason `SettingsScreen` gives at length:
    // `showSnackbar` suspends for the whole time it is up, so clearing afterwards leaves the
    // flag sitting in the (surviving) ViewModel for a recomposed screen to show a second time.
    LaunchedEffect(state.saveFailed) {
        if (!state.saveFailed) return@LaunchedEffect
        onEvent(ReminderEvent.MessageShown)
        snackbarHostState.showSnackbar(saveFailed)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ReminderTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
        ) {
            NextReminderHeader(state)

            PreferenceGroup(title = stringResource(R.string.reminder_group_reminder)) {
                // Not "Workout reminders" a second time: the bar above already says that, and
                // two controls-worth of the same words on one screen is how a user ends up
                // unsure which of them they just changed.
                PreferenceSwitchRow(
                    label = stringResource(R.string.reminder_enabled),
                    supporting = stringResource(R.string.reminder_enabled_detail),
                    checked = state.enabled,
                    onCheckedChange = { onEvent(ReminderEvent.Toggled(it)) },
                )

                // The two things the system can say about a reminder that is switched on: it
                // is not carrying the notification at all, or it is not carrying it on time.
                // Both are drawn only when the switch is on — telling somebody that something
                // they have turned off is also blocked is noise about a non-event.
                if (state.enabled) {
                    PermissionRows(
                        permission = state.permission,
                        isExact = state.isExact,
                        onEvent = onEvent,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                    )
                }
            }

            DaysGroup(state = state, onEvent = onEvent)

            PreferenceGroup(title = stringResource(R.string.reminder_group_time)) {
                PreferenceRow(
                    label = stringResource(R.string.reminder_time),
                    value = formatTimeOfDay(state.reminders.timeOfDay),
                    onClick = { onEvent(ReminderEvent.TimeRequested) },
                )
            }
        }
    }

    if (state.timePickerOpen) {
        TimePickerDialog(
            time = state.reminders.timeOfDay,
            title = stringResource(R.string.reminder_time),
            onConfirm = { onEvent(ReminderEvent.TimeSelected(it)) },
            onDismiss = { onEvent(ReminderEvent.TimeDismissed) },
        )
    }
}

/**
 * The header: the time, big, and one line saying what is going to happen to it.
 *
 * The time is drawn in the numeric display role — the wireframe's 56pt mono — because it is
 * the one figure on the screen and the screen is about it. It stays legible when the reminder
 * is off, in the quiet colour rather than removed: the user's chosen time is still their
 * setting, and blanking it would make turning the switch back on feel like starting over.
 */
@Composable
private fun NextReminderHeader(state: ReminderUiState) {
    OpenGymCard(
        modifier = Modifier.fillMaxWidth(),
        // The one card on the screen that is *live*, in the sense `OpenGymCard.accented`
        // reserves the accent border for: a reminder that is actually going to fire.
        accented = state.hasNextOccurrence,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.card),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Eyebrow(text = formatReminderDays(state.reminders.days))
            Text(
                text = formatTimeOfDay(state.reminders.timeOfDay),
                style = OpenGymTheme.typography.numericDisplay,
                color = if (state.hasNextOccurrence) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = state.statusLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The one line under the time, in the order the reasons actually block each other.
 *
 * The switch first, because nothing else matters while it is off; then the days, because an
 * empty schedule fires nowhere however good the permission is; then the permission, which is
 * the only one of the three the user cannot fix on this screen. Naming the *first* thing in
 * the way is what makes the line actionable — a sentence about the permission on a screen
 * whose switch is off would send somebody to system settings for nothing.
 */
@Composable
private fun ReminderUiState.statusLine(): String =
    when {
        !enabled -> stringResource(R.string.reminder_status_off)

        !reminders.isSchedulable -> stringResource(R.string.reminder_status_no_days)

        !permission.isGranted -> stringResource(R.string.reminder_status_blocked)

        nextOccurrence != null -> stringResource(R.string.reminder_status_next, formatSessionDate(nextOccurrence))

        // Unreachable while the three above hold — the ViewModel computes an occurrence for
        // exactly this case — and answered anyway rather than with an empty line, which would
        // read as a header that failed to load.
        else -> stringResource(R.string.reminder_status_off)
    }

/**
 * The days, with a note under them for the schedule that has none.
 *
 * The picker stays live while the reminder is off. Setting the days *before* turning it on is
 * the natural order to do this in, and a disabled row would make the switch the only thing on
 * the screen that works.
 */
@Composable
private fun DaysGroup(
    state: ReminderUiState,
    onEvent: (ReminderEvent) -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.reminder_group_days)) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            WeekdayPicker(
                selected = state.reminders.days,
                onToggle = { day, selected -> onEvent(ReminderEvent.DaySelected(day, selected)) },
            )
            if (!state.reminders.isSchedulable) {
                Text(
                    text = stringResource(R.string.reminder_days_empty_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Whatever the platform has to say about a reminder that is switched on.
 *
 * Two independent things, and up to both of them are drawn: the notification may not be
 * delivered at all, and it may not be delivered on time. They have different causes, different
 * ways out — two different pages of the system settings — and a user who has fixed one still
 * needs telling about the other.
 */
@Composable
private fun PermissionRows(
    permission: NotificationPermissionState,
    isExact: Boolean,
    onEvent: (ReminderEvent) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
) {
    when (permission) {
        NotificationPermissionState.GRANTED -> Unit

        // Refused, or switched off in system settings. The prompt will not come back, so the
        // only thing left to offer is the way to the screen that can undo it.
        NotificationPermissionState.DENIED -> {
            HairlineDivider()
            PreferenceRow(
                label = stringResource(R.string.settings_notifications_blocked),
                supporting = stringResource(R.string.settings_notifications_blocked_detail),
                external = true,
                onClick = onOpenNotificationSettings,
            )
        }

        // Never asked. The switch asks on the way past, so this row is for the user whose
        // reminder was already on and who therefore has nothing left to enable.
        NotificationPermissionState.NOT_REQUESTED -> {
            HairlineDivider()
            PreferenceRow(
                label = stringResource(R.string.settings_notifications_allow),
                supporting = stringResource(R.string.settings_notifications_allow_detail),
                onClick = { onEvent(ReminderEvent.PermissionRequested) },
            )
        }
    }

    if (!isExact) {
        HairlineDivider()
        // Stated first and offered second. The cost of not having the permission is measured in
        // minutes, so the row leads with what the user will actually notice rather than with a
        // demand — but the tap goes to the page that grants exact alarms, not to the
        // notification channels above, which carry no switch for this and would leave somebody
        // hunting for one that is not there.
        PreferenceRow(
            label = stringResource(R.string.reminder_inexact),
            supporting = stringResource(R.string.reminder_inexact_detail),
            external = true,
            onClick = onOpenExactAlarmSettings,
        )
    }
}

/** The screen's own bar. The shell hides its chrome outside the tabs — see `OpenGymApp`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTopBar(onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.settings_reminders),
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

/** Monday 27 April 2026 at 18:00 UTC, so the "next reminder" line is stable across runs. */
private const val NEXT_MONDAY_MILLIS = 1_777_312_800_000L

/**
 * Tall enough to capture the whole screen; a preview clips at the device height otherwise.
 *
 * The same trick `SettingsScreen`'s previews use, at two thirds the height — this screen is a
 * header and three cards rather than five groups of rows.
 */
private const val FULL_SCREEN_HEIGHT_DP = 900

/** A schedule that is nobody's default, so the preview is not a picture of the fallbacks. */
private val EARLY_MORNING = LocalTime.of(6, 30)

@Preview(name = "Reminder", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ReminderScreenPreview() {
    OpenGymTheme {
        ReminderScreen(
            state = ReminderUiState(
                enabled = true,
                nextOccurrence = NEXT_MONDAY_MILLIS,
            ),
            onEvent = {},
            onBack = {},
            onOpenNotificationSettings = {},
            onOpenExactAlarmSettings = {},
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
    name = "Reminder - twice the font",
    showBackground = true,
    heightDp = FULL_SCREEN_HEIGHT_DP * 2,
    fontScale = 2f,
)
@Composable
fun ReminderScreenLargeFontPreview() {
    OpenGymTheme {
        ReminderScreen(
            state = ReminderUiState(
                enabled = true,
                nextOccurrence = NEXT_MONDAY_MILLIS,
            ),
            onEvent = {},
            onBack = {},
            onOpenNotificationSettings = {},
            onOpenExactAlarmSettings = {},
        )
    }
}

@Preview(name = "Reminder - off, dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ReminderScreenOffPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ReminderScreen(
            state = ReminderUiState(
                enabled = false,
                reminders = ReminderPreferences(
                    days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY),
                    timeOfDay = EARLY_MORNING,
                ),
            ),
            onEvent = {},
            onBack = {},
            onOpenNotificationSettings = {},
            onOpenExactAlarmSettings = {},
        )
    }
}

/**
 * Blocked, and carrying an occurrence — which is the state the ViewModel actually produces.
 *
 * The date is computed from the switch and the days alone, so a blocked reminder still has
 * one. Leaving it out here would have made the preview a picture of a state that cannot
 * happen, and the header quiet for the wrong reason; with it, the golden is what proves the
 * card does not draw itself as live while the line under it says Android is dropping it.
 */
@Preview(name = "Reminder - blocked and inexact", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ReminderScreenBlockedPreview() {
    OpenGymTheme {
        ReminderScreen(
            state = ReminderUiState(
                enabled = true,
                nextOccurrence = NEXT_MONDAY_MILLIS,
                permission = NotificationPermissionState.DENIED,
                isExact = false,
            ),
            onEvent = {},
            onBack = {},
            onOpenNotificationSettings = {},
            onOpenExactAlarmSettings = {},
        )
    }
}

@Preview(name = "Reminder - no days", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ReminderScreenNoDaysPreview() {
    OpenGymTheme {
        ReminderScreen(
            state = ReminderUiState(
                enabled = true,
                reminders = ReminderPreferences(days = emptySet()),
            ),
            onEvent = {},
            onBack = {},
            onOpenNotificationSettings = {},
            onOpenExactAlarmSettings = {},
        )
    }
}
