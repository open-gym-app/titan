package com.titan.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.format.is24HourClock
import com.titan.core.ui.theme.TitanTheme
import java.time.LocalTime

/**
 * A time of day, picked on a clock face.
 *
 * ### Why this exists when Material 3 ships `TimePicker`
 *
 * Because `TimePicker` is only the dial. Material specifies a *dialog* around it — a title, a
 * confirm and a cancel — and does not ship one; every app builds it, and an app that builds it
 * at two call sites has two of them that will differ. There is one caller today (#39's workout
 * reminder) and #181's scheduled backup is the second, which is the rule this package already
 * follows: a component earns its place here on its second caller, and a screen that assembles
 * its own dialog around a Material primitive has restated arguments that will drift.
 *
 * ### Confirm and cancel, unlike the app's other pickers
 *
 * `ChoiceDialog` writes on the tap and offers no Save, because choosing *is* the whole gesture.
 * A clock face is not: setting 18:30 means dragging the hour hand past six other hours and then
 * the minute hand past thirty, and writing each of those would store eleven schedules on the
 * way to the one that was meant — and reschedule seven alarms for each. So this one is
 * committed, and dismissing it discards.
 *
 * ### Twelve or twenty-four hours is not this component's decision
 *
 * [is24HourClock] answers it from the same localized pattern the row that reads the choice back
 * renders through, so the dial and the value under it cannot disagree about whether there is a
 * meridiem.
 *
 * @param time the time to open on.
 * @param title what the dialog is asking for. The caller's, because "when should the reminder
 *   fire" and "when should the backup run" are different questions with one control.
 * @param onConfirm Save was pressed, with what the dial reads.
 * @param onDismiss cancelled — a tap outside, Cancel, or back. Nothing is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    time: LocalTime,
    title: String,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberTimePickerState(
        initialHour = time.hour,
        initialMinute = time.minute,
        is24Hour = is24HourClock(),
    )

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                // Centred over the dial rather than aligned to its left edge, which is the one
                // place in the app a title is not start-aligned: the control under it is round
                // and centred, and a heading hanging off its left reads as a mistake.
                textAlign = TextAlign.Center,
            )
        },
        text = {
            // Scrollable for the reason `ChoiceDialog` is: a dial plus a title plus a button
            // row is taller than a small phone at a large font scale, and a clipped clock face
            // has no way in.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(text = stringResource(R.string.time_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.time_picker_cancel))
            }
        },
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

/** The 24-hour dial's default: the wireframe's own 18:00. */
private val EVENING = LocalTime.of(18, 0)

/** A half-hour, so the minute hand is somewhere other than twelve o'clock. */
private val EARLY_MORNING = LocalTime.of(6, 30)

@Preview(name = "Time picker")
@Composable
fun TimePickerDialogPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TimePickerDialog(
            time = EVENING,
            title = "Reminder time",
            onConfirm = {},
            onDismiss = {},
            modifier = Modifier.padding(TitanTheme.spacing.screen),
        )
    }
}

@Preview(name = "Time picker, dark")
@Composable
fun TimePickerDialogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        TimePickerDialog(
            time = EARLY_MORNING,
            title = "Reminder time",
            onConfirm = {},
            onDismiss = {},
            modifier = Modifier.padding(TitanTheme.spacing.screen),
        )
    }
}
