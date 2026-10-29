package com.titan.core.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.timer.RestTimerState
import com.titan.core.ui.R
import com.titan.core.ui.format.formatAsClock
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration.Companion.seconds

/**
 * The next thing to do in the workout, at the bottom of the screen: where a thumb already is.
 *
 * ### Why the bottom, and why it is a call to action
 *
 * The phone is held in one hand between two sets, and the top of a 6-inch screen is the one band
 * a thumb cannot reach without the grip shifting. So the two things a lifter does over and over
 * — validate the set they just did, and look after the rest that follows — live down here, as
 * the one accent-filled control on the screen. Everything above it is the record; this is the
 * action.
 *
 * ### Three faces, one slot
 *
 * - **A set to validate** — the primary: the accent button naming the set and its exercise,
 *   with a square beside it that starts a rest without logging anything. Greyed, not hidden,
 *   while the row is still empty, so the dock does not jump around as the lifter types.
 * - **Nothing to validate** — a workout with no exercise to point at: the rest becomes the
 *   primary.
 * - **Resting** — the countdown, on a tinted strip that fills along its bottom edge as the rest
 *   goes. A tap raises the sheet to adjust, pause or skip.
 *
 * Validating a set starts the rest, so the first face slides into the third: the moment the
 * lifter is done is the moment the dock changes under their thumb.
 *
 * Drawn on the page's own background, like the header, with a hairline over it where the list
 * stops.
 *
 * @param state the rest timer, as the screen already has it.
 * @param onRestClick raise the rest sheet — over the rest that is going, or over a new one started
 *   for the occasion. Which of those it is is the caller's to decide.
 * @param nextSet the set the primary button validates, or null where there is none to point at.
 * @param onValidateSet validate [nextSet].
 * @param announces whether the countdown is the live region that reads the rest out — true once
 *   the sheet is away and the dock is the only countdown on screen. See the note on live
 *   regions at the top of `RestTimerSheet.kt`.
 * @param enabled false makes the dock inert, for a workout on its way out.
 */
@Composable
fun RestTimerDock(
    state: RestTimerState,
    onRestClick: () -> Unit,
    modifier: Modifier = Modifier,
    nextSet: DockSet? = null,
    onValidateSet: () -> Unit = {},
    announces: Boolean = false,
    enabled: Boolean = true,
) {
    val face = when {
        state is RestTimerState.Running || state is RestTimerState.Paused -> DockFace.RESTING
        nextSet != null -> DockFace.VALIDATE
        else -> DockFace.START_REST
    }

    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        HairlineDivider()
        AnimatedContent(
            targetState = face,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = TitanTheme.spacing.screen, vertical = TitanTheme.spacing.md),
            // Rising into place: the next thing to do arrives from where the thumb is.
            transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() },
            label = "rest dock",
        ) { shown ->
            when (shown) {
                DockFace.RESTING -> RestCard(
                    state = state,
                    onClick = onRestClick,
                    announces = announces,
                    enabled = enabled,
                )

                DockFace.VALIDATE -> Row(
                    horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Read off the live value rather than the animated one: while the faces
                    // cross, the set can change under it.
                    val set = nextSet ?: return@Row
                    PrimaryAction(
                        onClick = onValidateSet,
                        enabled = enabled && set.enabled,
                        icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                        label = set.label,
                        detail = set.detail,
                        modifier = Modifier.weight(1f),
                    )
                    StartRestSquare(onClick = onRestClick, enabled = enabled)
                }

                DockFace.START_REST -> PrimaryAction(
                    onClick = onRestClick,
                    enabled = enabled,
                    icon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                    label = stringResource(R.string.rest_dock_start),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The set the dock offers to validate, already worded — the screen owns the plural and the
 * exercise's name, this owns where they are drawn.
 *
 * @param label what pressing it does: "Complete set 4".
 * @param detail which exercise, so a lifter who scrolled away knows what the button is about.
 * @param enabled whether that row can be logged as it stands — the same rule its own card's
 *   button follows.
 */
@Immutable
data class DockSet(
    val label: String,
    val detail: String,
    val enabled: Boolean,
)

private enum class DockFace { RESTING, VALIDATE, START_REST }

/**
 * The accent button — built the way the card's own "Complete set" is, so the two read as one
 * control in two places: a Material [Button] at its own height, on the theme's button corner.
 * The exercise rides on the same line, quieter, rather than on a second line that would make
 * this the one tall button in the app.
 */
@Composable
private fun PrimaryAction(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(end = TitanTheme.spacing.sm)) { icon() }
        Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (detail != null) {
            Text(
                text = " · $detail",
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.let { it.copy(alpha = it.alpha * SECONDARY_ALPHA) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * A rest without a set first, beside the primary: an outlined square, the app's secondary,
 * at the primary's height so the two sit as one row.
 */
@Composable
private fun StartRestSquare(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    val description = stringResource(R.string.rest_dock_start)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .size(width = ButtonDefaults.MinHeight, height = ButtonDefaults.MinHeight)
            .semantics { contentDescription = description },
    ) {
        Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    }
}

/**
 * The rest going, as a strip that fills along its bottom edge.
 *
 * The primary's height and corner, so validating a set swaps one control for another of the same
 * size. One node for a screen reader, reading the time and offering the sheet, in the sheet's
 * own wording. The action is put back inside the `clearAndSetSemantics` rather than left to
 * the `Surface`: the clear throws away everything the rest of the chain contributed, the click
 * handler included, and a node that announces "double tap to activate" and then does nothing
 * is the way back into a swiped-away sheet gone for anyone driving the screen by screen reader.
 */
@Composable
private fun RestCard(
    state: RestTimerState,
    onClick: () -> Unit,
    announces: Boolean,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val running = state is RestTimerState.Running
    // The accent means time is passing, so a paused rest does not get it — the rule the sheet's
    // figure and bar follow.
    val accent = if (running) colors.primary else colors.onSurfaceVariant
    val spoken = if (announces) state.announcement() else spokenRemaining(state.remaining())
    val reopen = stringResource(R.string.rest_timer_reopen)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(ButtonDefaults.MinHeight)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (announces) liveRegion = LiveRegionMode.Polite
                if (enabled) {
                    onClick(label = reopen) {
                        onClick()
                        true
                    }
                }
            },
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = if (running) colors.primary.copy(alpha = TINT_ALPHA) else colors.surfaceContainerHigh,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TitanTheme.spacing.lg)
                    .align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.remaining().formatAsClock(),
                    style = TitanTheme.typography.numeric,
                    color = accent,
                )
                Eyebrow(
                    text = state.statusLabel(),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
            // The sheet's own bar, so the two cannot disagree about how far through the rest is.
            RestProgressBar(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = accent,
                trackColor = Color.Transparent,
                height = BAR_HEIGHT,
            )
        }
    }
}

private val BAR_HEIGHT: Dp = 3.dp

/** The exercise's name after the action: read second, so drawn quieter. */
private const val SECONDARY_ALPHA = 0.8f

/** The accent as a tint rather than a fill, behind a running countdown. */
private const val TINT_ALPHA = 0.12f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Rest dock", widthDp = 360)
@Composable
fun RestTimerDockPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        RestTimerDockCatalogue()
    }
}

@Preview(name = "Rest dock, dark", widthDp = 360)
@Composable
fun RestTimerDockDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        RestTimerDockCatalogue()
    }
}

@Composable
private fun RestTimerDockCatalogue() {
    Column(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        RestTimerDock(
            state = RestTimerState.Idle,
            onRestClick = {},
            nextSet = DockSet(label = "Complete set 4", detail = "Ab Crunch Machine", enabled = true),
        )
        RestTimerDock(
            state = RestTimerState.Idle,
            onRestClick = {},
            nextSet = DockSet(label = "Complete set 1", detail = "Bench press", enabled = false),
        )
        RestTimerDock(state = RestTimerState.Idle, onRestClick = {})
        RestTimerDock(state = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds), onRestClick = {})
        RestTimerDock(state = RestTimerState.Paused(remaining = 83.seconds, total = 120.seconds), onRestClick = {})
    }
}
