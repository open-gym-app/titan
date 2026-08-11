package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.timer.RestTimerState
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatAsClock
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.core.ui.theme.PillShape
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * The rest between two sets.
 *
 * Three composables over one [RestTimerState], because the same countdown is wanted at three
 * sizes:
 *
 * - [RestTimerSheet] is the body of the bottom sheet that comes up when a set is logged. It
 *   is the sheet's *content*, not a `ModalBottomSheet` — the sheet, its state and when it is
 *   shown belong to the screen (#98), and a component that owned them could not be driven
 *   from a test or drawn in a preview.
 * - [RestCountdown] is the readout on its own: the eyebrow, the figure and the bar. It is
 *   what a compact header uses when the sheet has been dismissed but the rest is still
 *   running.
 * - [RestTimerIndicator] is the one-line pill for a top app bar.
 *
 * None of them ticks. [RestTimerState] arrives already counting from
 * [com.opengym.core.domain.timer.RestTimerController], which is the thing that owns a clock;
 * these draw whatever they are handed, including a `Running` that has stopped changing,
 * which is exactly what a paused screen should look like.
 */

/**
 * The rest timer, as a bottom sheet's contents.
 *
 * @param state what the timer is doing. [RestTimerState.Idle] is drawn — as a zeroed,
 *   disabled sheet — rather than refused, because a sheet mid-dismissal composes one more
 *   frame after the timer has been cancelled and a `require` there would crash on the way
 *   out.
 * @param onAdjust called with a signed [Duration]: `+15s` from the extend button, `-15s`
 *   from the shorten one. One lambda rather than two, because the caller's handler is
 *   `controller.adjust(delta)` either way and two would be two places to get the sign wrong.
 * @param onSkip ends the rest. The primary action, and the one the thumb lands on.
 * @param onTogglePause pauses a running rest or resumes a paused one. Null hides the
 *   control — a screen that has no use for a paused rest should not draw a button for it.
 * @param nextSet what is coming, formatted by the caller: `80 kg × 5`. The reason the sheet
 *   is worth looking at rather than waiting out.
 * @param adjustStep how much the two adjust buttons move the countdown by. Labelled from
 *   this value, so a screen that uses 30-second steps gets buttons that say so.
 */
@Composable
fun RestTimerSheet(
    state: RestTimerState,
    onAdjust: (Duration) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onTogglePause: (() -> Unit)? = null,
    nextSet: String? = null,
    adjustStep: Duration = RestTimerDefaults.ADJUST_STEP,
) {
    val adjustable = state is RestTimerState.Running || state is RestTimerState.Paused
    val stepSeconds = adjustStep.inWholeSeconds.toInt()
    val shorterLabel = stringResource(R.string.rest_timer_shorter, stepSeconds)
    val longerLabel = stringResource(R.string.rest_timer_longer, stepSeconds)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = OpenGymTheme.spacing.screen,
                end = OpenGymTheme.spacing.screen,
                bottom = OpenGymTheme.spacing.section,
            ),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        RestCountdown(state = state, nextSet = nextSet, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
            OutlinedButton(
                onClick = { onAdjust(-adjustStep) },
                enabled = adjustable,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = shorterLabel },
            ) {
                Text(
                    text = stringResource(R.string.rest_timer_shorter_label, stepSeconds),
                    style = OpenGymTheme.typography.numericSmall,
                )
            }

            if (onTogglePause != null) {
                PauseResumeButton(
                    state = state,
                    onTogglePause = onTogglePause,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedButton(
                onClick = { onAdjust(adjustStep) },
                enabled = adjustable,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = longerLabel },
            ) {
                Text(
                    text = stringResource(R.string.rest_timer_longer_label, stepSeconds),
                    style = OpenGymTheme.typography.numericSmall,
                )
            }
        }

        Button(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (state is RestTimerState.Finished) {
                    stringResource(R.string.rest_timer_next_set)
                } else {
                    stringResource(R.string.rest_timer_skip)
                },
            )
        }
    }
}

/**
 * The readout alone: what the timer is doing, how long is left, and how far through it is.
 *
 * Extracted from [RestTimerSheet] so a compact header can reuse the exact figure the sheet
 * shows. Two things must not drift apart here — a header that rounded `0:00.7` down while the
 * sheet rounded it up would show two different times for one rest.
 *
 * @param valueStyle the type role the figure is set in. The default fills a sheet; pass
 *   `OpenGymTheme.typography.numericLarge` for a readout inside a card.
 */
@Composable
fun RestCountdown(
    state: RestTimerState,
    modifier: Modifier = Modifier,
    nextSet: String? = null,
    valueStyle: TextStyle = OpenGymTheme.typography.numericDisplay,
) {
    val remaining = state.remaining()
    val total = state.total()
    val spoken = spokenRemaining(remaining)
    val separator = stringResource(R.string.exercise_meta_separator)
    val supporting = listOfNotNull(
        total?.let { stringResource(R.string.rest_timer_of_total, it.formatAsClock()) },
        nextSet?.let { stringResource(R.string.rest_timer_up_next, it) },
    ).joinToString(separator)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
    ) {
        Eyebrow(text = state.statusLabel(), color = state.accentOrMuted())
        Text(
            text = remaining.formatAsClock(),
            style = valueStyle,
            color = state.accentOrMuted(),
            modifier = Modifier.semantics { contentDescription = spoken },
        )
        if (supporting.isNotEmpty()) {
            Text(
                text = supporting,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { state.elapsedFraction() },
            // Padding before height, not after: a `height` that follows a `padding` sizes the
            // bar, while one that precedes it sizes the bar *and* the gap, leaving a 2dp line.
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OpenGymTheme.spacing.xs)
                .height(PROGRESS_HEIGHT),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            strokeCap = StrokeCap.Round,
            // Material 1.4 splits the bar with a gap and a stop dot. Neither is wanted on a
            // 6dp rest bar: the gap reads as a second segment and the dot as a target.
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/**
 * The countdown as a pill, for a header: `1:23`.
 *
 * The one place the accent is spent on something small. A running rest is the only thing on
 * the active-session screen that is *changing*, and it has to be findable without the sheet
 * being open.
 */
@Composable
fun RestTimerIndicator(
    state: RestTimerState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val remaining = state.remaining()
    val running = state is RestTimerState.Running
    val spoken = spokenRemaining(remaining)

    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .background(
                color = if (running) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                shape = PillShape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = OpenGymTheme.spacing.md, vertical = OpenGymTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = remaining.formatAsClock(),
            style = OpenGymTheme.typography.numeric,
            color = if (running) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Defaults for the rest timer components. */
object RestTimerDefaults {

    /**
     * How much the adjust buttons move a rest by.
     *
     * Fifteen seconds because it is the smallest step that is worth pressing a button for —
     * a rest is a minute or three, and five-second nudges are a fidget — and because two
     * presses is the common case of "give me another half minute".
     */
    val ADJUST_STEP: Duration = 15.seconds
}

// ─── State, read four ways ──────────────────────────────────────────────────────────────

/*
 * Every `when` over [RestTimerState] in this file is exhaustive over the sealed interface, so
 * a state added to the domain is a compile error here rather than a sheet that renders blank.
 */

private fun RestTimerState.remaining(): Duration = when (this) {
    is RestTimerState.Running -> remaining
    is RestTimerState.Paused -> remaining
    RestTimerState.Finished, RestTimerState.Idle -> Duration.ZERO
}

/** The countdown's denominator, or null where there is nothing being counted down. */
private fun RestTimerState.total(): Duration? = when (this) {
    is RestTimerState.Running -> total
    is RestTimerState.Paused -> total
    RestTimerState.Finished, RestTimerState.Idle -> null
}

/**
 * How much of the rest has gone, 0..1.
 *
 * `Finished` is a full bar and `Idle` an empty one. A zero-length total would divide by zero;
 * `RestTimerState` forbids it for `Running`, but `Paused` allows a zero remainder and total,
 * so it is guarded rather than assumed.
 */
private fun RestTimerState.elapsedFraction(): Float {
    val total = total() ?: return if (this is RestTimerState.Finished) 1f else 0f
    if (total <= Duration.ZERO) return 1f
    return ((total - remaining()) / total).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun RestTimerState.statusLabel(): String = when (this) {
    is RestTimerState.Running -> stringResource(R.string.rest_timer_running)
    is RestTimerState.Paused -> stringResource(R.string.rest_timer_paused)
    RestTimerState.Finished -> stringResource(R.string.rest_timer_finished)
    RestTimerState.Idle -> stringResource(R.string.rest_timer_idle)
}

/**
 * The accent while the rest is live, a neutral once it is not.
 *
 * A paused countdown in the accent is the bug this exists to prevent: the accent means
 * "happening now", and a frozen figure wearing it is the screen saying time is passing when
 * it is not.
 */
@Composable
private fun RestTimerState.accentOrMuted(): Color = when (this) {
    is RestTimerState.Running, RestTimerState.Finished -> MaterialTheme.colorScheme.primary
    is RestTimerState.Paused, RestTimerState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun spokenRemaining(remaining: Duration): String =
    stringResource(R.string.rest_timer_remaining, remaining.formatAsClock())

@Composable
private fun PauseResumeButton(
    state: RestTimerState,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = state is RestTimerState.Paused
    val spoken = if (paused) {
        stringResource(R.string.rest_timer_resume)
    } else {
        stringResource(R.string.rest_timer_pause)
    }

    OutlinedButton(
        onClick = onTogglePause,
        enabled = state is RestTimerState.Running || paused,
        modifier = modifier.semantics { contentDescription = spoken },
    ) {
        Icon(
            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = null,
        )
    }
}

/** The height of the progress bar. Thick enough to read across a room, thin enough to rule. */
private val PROGRESS_HEIGHT: Dp = 6.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Rest timer - running", widthDp = 360)
@Composable
fun RestTimerSheetRunningPreview() {
    RestSheetPreview(
        theme = ThemePreference.LIGHT,
        state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
    )
}

@Preview(name = "Rest timer - running, dark", widthDp = 360)
@Composable
fun RestTimerSheetRunningDarkPreview() {
    RestSheetPreview(
        theme = ThemePreference.DARK,
        state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
    )
}

@Preview(name = "Rest timer - paused", widthDp = 360)
@Composable
fun RestTimerSheetPausedPreview() {
    RestSheetPreview(
        theme = ThemePreference.LIGHT,
        state = RestTimerState.Paused(remaining = 83.seconds, total = 120.seconds),
    )
}

@Preview(name = "Rest timer - finished", widthDp = 360)
@Composable
fun RestTimerSheetFinishedPreview() {
    RestSheetPreview(theme = ThemePreference.LIGHT, state = RestTimerState.Finished)
}

@Preview(name = "Rest timer - header indicator", widthDp = 360)
@Composable
fun RestTimerIndicatorPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(OpenGymTheme.spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            RestTimerIndicator(
                state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds),
                onClick = {},
            )
            RestTimerIndicator(state = RestTimerState.Paused(remaining = 83.seconds, total = 120.seconds))
            RestTimerIndicator(state = RestTimerState.Idle)
        }
    }
}

@Composable
private fun RestSheetPreview(theme: ThemePreference, state: RestTimerState) {
    OpenGymTheme(theme = theme) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(top = OpenGymTheme.spacing.section),
        ) {
            RestTimerSheet(
                state = state,
                onAdjust = {},
                onSkip = {},
                onTogglePause = {},
                nextSet = "80 kg × 5",
            )
        }
    }
}
