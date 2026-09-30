package com.titan.core.ui.component

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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.timer.RestTimerState
import com.titan.core.ui.R
import com.titan.core.ui.format.formatAsClock
import com.titan.core.ui.theme.PillShape
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * The rest between two sets.
 *
 * Four composables over one [RestTimerState], because the same countdown is wanted at several
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
 * - [RestProgressBar] is the bar alone, which the sheet and the header both draw so that the
 *   two cannot disagree about how far through a rest is.
 *
 * None of them ticks. [RestTimerState] arrives already counting from
 * [com.titan.core.domain.timer.RestTimerController], which is the thing that owns a clock;
 * these draw whatever they are handed, including a `Running` that has stopped changing,
 * which is exactly what a paused screen should look like.
 *
 * ### What a screen reader hears while the rest runs
 *
 * A countdown is the one thing on the screen that changes without a tap, and a value that
 * changes silently does not exist to TalkBack. So the readout carries a **polite live region**
 * — the status eyebrow in [RestCountdown], or the pill in [RestTimerIndicator] where the sheet
 * has been swiped away — and what it says is [announcement]: the status and the time left *to
 * the nearest quarter minute*. Not the exact second, and not on the exact figure: a live
 * region speaks every time its text changes, and a region that changed sixty times a minute
 * would talk over everything else on the screen, including the set the user is trying to log.
 * Four times a minute is a cadence a lifter can rest to; the exact figure is still one swipe
 * away on the digits themselves.
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
                start = TitanTheme.spacing.screen,
                end = TitanTheme.spacing.screen,
                bottom = TitanTheme.spacing.section,
            ),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        RestCountdown(state = state, nextSet = nextSet, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            OutlinedButton(
                onClick = { onAdjust(-adjustStep) },
                enabled = adjustable,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = shorterLabel },
            ) {
                Text(
                    text = stringResource(R.string.rest_timer_shorter_label, stepSeconds),
                    style = TitanTheme.typography.numericSmall,
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
                    style = TitanTheme.typography.numericSmall,
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
 *   `TitanTheme.typography.numericLarge` for a readout inside a card.
 */
@Composable
fun RestCountdown(
    state: RestTimerState,
    modifier: Modifier = Modifier,
    nextSet: String? = null,
    valueStyle: TextStyle = TitanTheme.typography.numericDisplay,
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
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
    ) {
        // The live region — see the note at the top of the file. On the eyebrow, whose drawn
        // text is the status, rather than on the figure, which has to keep saying the exact
        // second to whoever lands on it.
        Eyebrow(
            text = state.statusLabel(),
            color = state.accentOrMuted(),
            contentDescription = state.announcement(),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = remaining.formatAsClock(),
            style = valueStyle,
            color = state.accentOrMuted(),
            modifier = Modifier.semantics { contentDescription = spoken },
        )
        if (supporting.isNotEmpty()) {
            Text(
                text = supporting,
                style = TitanTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RestProgressBar(
            state = state,
            // Padding before height, not after — see [RestProgressBar], which applies the
            // height last for exactly this reason.
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TitanTheme.spacing.xs),
        )
    }
}

/**
 * How far through the rest is, as one bar that fills from empty to full.
 *
 * The whole of the countdown that is *not* a number, and the reason it is public: the sheet
 * and the header both draw it, and they must draw the same thing. A header that had a bar of
 * its own — or, worse, borrowed the one next to it — would be a second answer to "how much of
 * this rest is left", and two bars disagreeing about that is the one thing a rest timer cannot
 * afford.
 *
 * Continuous, never segmented. It sits a few dp from [SessionProgressRail], which *is*
 * segmented and is counting something else entirely — exercises done, not seconds passed — and
 * the difference in shape is what keeps the two readable as two different facts.
 *
 * @param color the fill. Defaults to the scheme's primary; the workout header passes its own
 *   accent for a running rest and a neutral for a paused one, on the same rule the big figure
 *   follows — the accent means time is passing, so a frozen bar must not wear it.
 * @param trackColor what the unfilled remainder is drawn on.
 * @param height how thick the bar is. The default fills a sheet; a header rail passes its own.
 */
@Composable
fun RestProgressBar(
    state: RestTimerState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    height: Dp = PROGRESS_HEIGHT,
) {
    LinearProgressIndicator(
        progress = { state.elapsedFraction() },
        // Height last: a `height` that follows a `padding` sizes the bar, while one that
        // precedes it sizes the bar *and* the gap, leaving a 2dp line.
        modifier = modifier.height(height),
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round,
        // Material 1.4 splits the bar with a gap and a stop dot. Neither is wanted on a rest
        // bar: the gap reads as a second segment and the dot as a target.
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/**
 * The countdown as a pill, for a header: `1:23`.
 *
 * The one place the accent is spent on something small. A running rest is the only thing on
 * the active-session screen that is *changing*, and it has to be findable without the sheet
 * being open — which is what makes this the way back into a sheet the user swiped away.
 *
 * @param onClick reopens the sheet. Null draws the pill inert, which is what a header with
 *   nowhere to go wants; where it is given, the pill announces itself as the way back rather
 *   than as four digits that happen to be tappable.
 * @param colors which two pairs it is drawn in. Defaulted to the colour scheme and overridden
 *   by the one caller that is not on it — the active workout's header, which is a near-black
 *   band in both schemes and would swallow a `primaryContainer` pill. See
 *   [RestTimerDefaults.sessionChromeIndicatorColors].
 * @param announces whether this pill is the live region for the rest — true when it is the only
 *   readout on screen, which is the sheet swiped away. False while the sheet is up, or the same
 *   quarter-minute would be spoken twice, once from each window. Announcing, the pill says
 *   [announcement] rather than the exact remainder, since the description *is* what the region
 *   speaks each time it changes; see the note at the top of the file.
 */
@Composable
fun RestTimerIndicator(
    state: RestTimerState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: RestTimerIndicatorColors = RestTimerDefaults.indicatorColors(),
    announces: Boolean = false,
) {
    val remaining = state.remaining()
    val running = state is RestTimerState.Running
    val spoken = if (announces) state.announcement() else spokenRemaining(remaining)
    val reopen = stringResource(R.string.rest_timer_reopen)

    Row(
        modifier = modifier
            // One stop in the traversal order, reading the time and — where there is one — what
            // pressing it does. The digits on their own are a number with no noun near them.
            // The action goes in here with the label, and not as a bare label over the
            // `clickable` below: this is `clearAndSetSemantics`, which throws away everything
            // the rest of the chain contributed — including the clickable's handler. Labelling
            // without re-supplying it leaves a node that announces "double tap to activate",
            // takes the tap and does nothing, which is the way back into a swiped-away sheet
            // gone for anyone driving the screen by screen reader. (The plain `.semantics`
            // call elsewhere in this codebase *merges*, which is why a null action is right
            // there and wrong here.)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (announces) liveRegion = LiveRegionMode.Polite
                if (onClick != null) {
                    onClick(label = reopen) {
                        onClick()
                        true
                    }
                }
            }
            .background(
                color = if (running) colors.runningContainer else colors.restingContainer,
                shape = PillShape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = TitanTheme.spacing.md, vertical = TitanTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = remaining.formatAsClock(),
            style = TitanTheme.typography.numeric,
            color = if (running) colors.runningContent else colors.restingContent,
        )
    }
}

/**
 * The two pairs [RestTimerIndicator] is drawn in: one for a countdown that is moving, one for
 * a countdown that is not.
 *
 * A pair each rather than a single container and content, because the difference between them
 * is the whole of what the pill communicates from across a room — the accent means the clock is
 * running, and a paused rest wearing it would be the header saying time is passing when it is
 * not. That is the same rule [RestCountdown] applies to the big figure, stated once for each
 * ground the pill can sit on.
 */
@Immutable
data class RestTimerIndicatorColors(
    val runningContainer: Color,
    val runningContent: Color,
    val restingContainer: Color,
    val restingContent: Color,
)

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

    /** [RestTimerIndicator] on the colour scheme, which is where any ordinary screen puts it. */
    @Composable
    fun indicatorColors(): RestTimerIndicatorColors =
        RestTimerIndicatorColors(
            runningContainer = MaterialTheme.colorScheme.primaryContainer,
            runningContent = MaterialTheme.colorScheme.onPrimaryContainer,
            restingContainer = MaterialTheme.colorScheme.surfaceContainer,
            restingContent = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /**
     * [RestTimerIndicator] on the active workout's own near-black band.
     *
     * The header does not invert with the scheme — see
     * [com.titan.core.ui.theme.TitanSessionChrome] — so a pill built from the scheme's
     * containers would be a light chip on a dark bar in one scheme and invisible in the other.
     * Both pairs here are ones the chrome already carries and the contrast test already holds.
     */
    @Composable
    fun sessionChromeIndicatorColors(): RestTimerIndicatorColors =
        with(TitanTheme.sessionChrome) {
            RestTimerIndicatorColors(
                runningContainer = accent,
                runningContent = onAccent,
                restingContainer = fill,
                restingContent = onSurface,
            )
        }
}

// ─── State, read four ways ──────────────────────────────────────────────────────────────

/*
 * Every `when` over [RestTimerState] in this file is exhaustive over the sealed interface, so
 * a state added to the domain is a compile error here rather than a sheet that renders blank.
 */

private fun RestTimerState.remaining(): Duration =
    when (this) {
        is RestTimerState.Running -> remaining
        is RestTimerState.Paused -> remaining
        RestTimerState.Finished, RestTimerState.Idle -> Duration.ZERO
    }

/** The countdown's denominator, or null where there is nothing being counted down. */
private fun RestTimerState.total(): Duration? =
    when (this) {
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
private fun RestTimerState.statusLabel(): String =
    when (this) {
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
private fun RestTimerState.accentOrMuted(): Color =
    when (this) {
        is RestTimerState.Running, RestTimerState.Finished -> MaterialTheme.colorScheme.primary
        is RestTimerState.Paused, RestTimerState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun spokenRemaining(remaining: Duration): String =
    stringResource(R.string.rest_timer_remaining, remaining.formatAsClock())

/**
 * What the live region says — see the note at the top of the file.
 *
 * A running rest is announced to the nearest [ANNOUNCEMENT_STEP] *above* the remainder, so `1:23`
 * is "1:30 left" and the sentence changes exactly when the countdown crosses a quarter-minute:
 * four announcements a minute, never more. Rounded up rather than down because "0:00 left" with
 * fourteen seconds still to go is a lie the alert then contradicts; the last thing said before
 * the rest ends is "0:15 left", and the next is "Rest complete". A paused rest is frozen, so it
 * is announced exactly — the figure is not going to change under the sentence.
 */
@Composable
private fun RestTimerState.announcement(): String =
    when (this) {
        is RestTimerState.Running ->
            stringResource(R.string.rest_timer_live_running, remaining.roundedUpTo(ANNOUNCEMENT_STEP).formatAsClock())

        is RestTimerState.Paused -> stringResource(R.string.rest_timer_live_paused, remaining.formatAsClock())

        RestTimerState.Finished -> stringResource(R.string.rest_timer_finished)

        RestTimerState.Idle -> stringResource(R.string.rest_timer_idle)
    }

/** The smallest multiple of [step] that is not below this duration. Zero stays zero. */
private fun Duration.roundedUpTo(step: Duration): Duration {
    val steps = inWholeMilliseconds / step.inWholeMilliseconds
    val remainder = inWholeMilliseconds % step.inWholeMilliseconds
    return step * (steps + if (remainder > 0) 1 else 0).toInt()
}

/** How often a running rest is announced. See [announcement]. */
private val ANNOUNCEMENT_STEP: Duration = 15.seconds

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
    TitanTheme(theme = ThemePreference.LIGHT) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(TitanTheme.spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
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
private fun RestSheetPreview(
    theme: ThemePreference,
    state: RestTimerState,
) {
    TitanTheme(theme = theme) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(top = TitanTheme.spacing.section),
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
