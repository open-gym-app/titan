package com.titan.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.format.formatAsClock
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The stopwatch under a hold's entry row: start it, hold the plank, stop it, and what it read
 * goes into the time field (#M7-17).
 *
 * A shortcut for typing, and never more than one: stopping it writes the figure into the field
 * exactly as if the lifter had typed it, and the set is still logged by the tick. A stopwatch
 * that logged the set on stop would write a set nobody has looked at, and a hold whose timing
 * went wrong — a start pressed late — is corrected by typing over the figure, which is always
 * possible.
 *
 * ### Two states, one place
 *
 * Idle, it is a quiet outlined button, because on most sets of most workouts nobody uses it and
 * it must not compete with the tick. Running, the figure is the thing on the row worth looking
 * at from the floor of a plank, so it is drawn in the numeric role in the accent with the stop
 * control beside it — the same accent the rest countdown carries for the same reason: it is the
 * clock that is happening now.
 *
 * The figure is not a live region. A hold is watched rather than listened to, and an
 * announcement a second for its whole length is noise a screen reader user would turn the
 * stopwatch off to escape; the stop control speaks what it reads instead, so the figure is one
 * swipe away and is said when it matters, which is when the hold ends.
 *
 * @param setNumber the set being timed, which the controls name — a workout of two holds has two
 *   of these on screen, and "Start timer" twice is two identical controls.
 * @param elapsed how long the hold has lasted, or **null** while the stopwatch is not running.
 * @param onStart start timing. Required even where [enabled] is false, so the control keeps its
 *   shape.
 * @param onStop stop timing, and put the figure in the field.
 * @param enabled false leaves the control drawn and inert — a paused workout, or a hold already
 *   being timed on another exercise. One stopwatch per workout: a lifter holds one plank at a
 *   time.
 */
@Composable
fun HoldStopwatch(
    setNumber: Int,
    elapsed: Duration?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (elapsed == null) {
        val spoken = stringResource(R.string.stopwatch_start_description, setNumber)
        OutlinedButton(
            onClick = onStart,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_TARGET)
                .semantics { contentDescription = spoken },
        ) {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = null,
                modifier = Modifier.padding(end = TitanTheme.spacing.sm),
            )
            Text(text = stringResource(R.string.stopwatch_start), style = MaterialTheme.typography.labelLarge)
        }
        return
    }

    val reading = elapsed.formatAsClock()
    val spoken = stringResource(R.string.stopwatch_stop_description, setNumber, reading)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = reading,
            style = TitanTheme.typography.numericLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                // Said by the stop control — see the class note.
                .clearAndSetSemantics { },
        )
        FilledTonalButton(
            onClick = onStop,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .heightIn(min = MIN_TOUCH_TARGET)
                .semantics { contentDescription = spoken },
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.padding(end = TitanTheme.spacing.sm),
            )
            Text(text = stringResource(R.string.stopwatch_stop), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Hold stopwatch - idle", widthDp = 360)
@Composable
fun HoldStopwatchIdlePreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TitanCard(modifier = Modifier.padding(TitanTheme.spacing.screen)) {
            HoldStopwatch(
                setNumber = 2,
                elapsed = null,
                onStart = {},
                onStop = {},
                modifier = Modifier.padding(TitanTheme.spacing.card),
            )
        }
    }
}

@Preview(name = "Hold stopwatch - running, dark", widthDp = 360)
@Composable
fun HoldStopwatchRunningDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        TitanCard(modifier = Modifier.padding(TitanTheme.spacing.screen)) {
            HoldStopwatch(
                setNumber = 2,
                elapsed = 47.seconds,
                onStart = {},
                onStop = {},
                modifier = Modifier.padding(TitanTheme.spacing.card),
            )
        }
    }
}
