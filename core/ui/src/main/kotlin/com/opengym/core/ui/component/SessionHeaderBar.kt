package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatAsElapsed
import com.opengym.core.ui.theme.OpenGymSessionChrome
import com.opengym.core.ui.theme.OpenGymTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The bar the active workout runs under: what you are doing, how long you have been doing it,
 * and the two ways out.
 *
 * Drawn on [OpenGymTheme.sessionChrome] rather than on the colour scheme, so it stays the same
 * dark band in light and dark — see [OpenGymSessionChrome] for why a workout gets a surface of
 * its own and why it does not invert.
 *
 * Stateless like everything else here: it renders an elapsed [Duration] and reports taps. It
 * does not tick. The clock is the screen's, because the screen is the thing that knows whether
 * the session is paused, and a component running its own coroutine would be a second answer to
 * that question.
 *
 * @param name what the session is called, already resolved — a template's name, or the
 *   date-derived fallback for a session started empty. The component does not fall back itself:
 *   `workout_session.name` being null means *unnamed*, and inventing a title here would put the
 *   invention below the level that knows what the session is.
 * @param elapsed how long the user has been working out, which is wall-clock time less every
 *   paused stretch. Rendered with `formatAsElapsed`, so it reads `23:14` under an hour and
 *   `1:04:22` over it.
 * @param progress how far through the planned exercises the workout is, one entry per exercise
 *   in the order they are performed. See [SessionProgress].
 * @param isPaused whether the session is suspended, which is the whole of what the pause
 *   control's two faces mean.
 * @param onPauseToggle pause if running, resume if paused.
 * @param onFinish end the workout and go to the recap.
 * @param onDiscard throw the workout away. Optional, and absent by default: a bar that offered
 *   it next to finish at all times would put "delete this workout" one mistap from "I am done",
 *   so a caller passes it only where it is the action the user is looking for.
 * @param onNameClick rename the workout. The name itself is the affordance rather than a pencil
 *   beside it: the bar already carries three controls and a clock across 320dp, and a fourth
 *   would come out of the clock's width — which is the one thing on it that must not be clipped.
 *   Null leaves the name inert, which is what a caller with nothing to write wants.
 */
@Composable
fun SessionHeaderBar(
    name: String,
    elapsed: Duration,
    modifier: Modifier = Modifier,
    progress: SessionProgress = SessionProgress(),
    isPaused: Boolean = false,
    onPauseToggle: () -> Unit = {},
    onFinish: () -> Unit = {},
    onDiscard: (() -> Unit)? = null,
    onNameClick: (() -> Unit)? = null,
) {
    val chrome = OpenGymTheme.sessionChrome

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(chrome.surface)
            .padding(horizontal = OpenGymTheme.spacing.screen, vertical = OpenGymTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val clock = elapsed.formatAsElapsed()
                // The clock is announced with what it is a clock *of*; read as bare text it is
                // four digits with no noun anywhere near them.
                val spoken = stringResource(
                    if (isPaused) R.string.session_elapsed_paused else R.string.session_elapsed,
                    clock,
                )

                val rename = stringResource(R.string.session_header_rename)
                Eyebrow(
                    text = name,
                    color = chrome.onSurfaceVariant,
                    modifier = if (onNameClick != null) {
                        Modifier
                            .clickable(onClick = onNameClick)
                            .semantics { onClick(label = rename, action = null) }
                    } else {
                        Modifier
                    },
                )
                Text(
                    text = clock,
                    style = OpenGymTheme.typography.numericLarge,
                    color = chrome.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = OpenGymTheme.spacing.xs)
                        .semantics { contentDescription = spoken },
                )
            }

            if (onDiscard != null) {
                ChromeButton(
                    onClick = onDiscard,
                    description = stringResource(R.string.session_header_discard),
                    container = chrome.fill,
                    content = chrome.onSurface,
                ) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                }
            }

            ChromeButton(
                onClick = onPauseToggle,
                description = stringResource(
                    if (isPaused) R.string.session_header_resume else R.string.session_header_pause,
                ),
                container = chrome.fill,
                content = chrome.onSurface,
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                )
            }

            ChromeButton(
                onClick = onFinish,
                description = stringResource(R.string.session_header_finish),
                container = chrome.accent,
                content = chrome.onAccent,
                width = FINISH_WIDTH,
            ) {
                Eyebrow(
                    text = stringResource(R.string.session_header_finish_label),
                    color = chrome.onAccent,
                )
            }
        }

        if (progress.total > 0) {
            SessionProgressRail(progress = progress, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The workout as a row of slivers: one per exercise, filled for the ones that are behind you.
 *
 * The one figure on the header that is a *shape* rather than a number, because it answers a
 * question — am I nearly done — that a fraction answers worse. `4 / 8` has to be read and
 * divided; a rail that is half orange does not.
 *
 * Hidden from screen readers, with the same fact restated as one description on the whole row.
 * Eight unlabelled slivers is eight stops in the traversal order, and none of them means
 * anything on its own.
 */
@Composable
fun SessionProgressRail(progress: SessionProgress, modifier: Modifier = Modifier) {
    val chrome = OpenGymTheme.sessionChrome
    val description = stringResource(
        R.string.session_progress,
        progress.completed,
        progress.total,
    )

    Row(
        modifier = modifier
            .height(RAIL_HEIGHT)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(RAIL_GAP),
    ) {
        repeat(progress.total) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        color = when {
                            index < progress.completed -> chrome.accent
                            // The exercise being worked is the accent held back: it is
                            // underway rather than behind you, and drawing it either fully on
                            // or fully off would make the rail lie by one exercise in one
                            // direction or the other.
                            index == progress.completed -> chrome.accent.copy(alpha = CURRENT_ALPHA)
                            else -> chrome.track
                        },
                        shape = RoundedCornerShape(RAIL_HEIGHT / 2),
                    ),
            )
        }
    }
}

/**
 * How far through its exercises a workout is.
 *
 * Two counts rather than a fraction, because the rail draws one sliver per exercise and a
 * `Float` would have thrown away the number of them. [completed] is also allowed to equal
 * [total] — a workout whose last exercise is done but which has not been finished yet is a real
 * state, and the rail is entirely filled for it.
 *
 * @param completed exercises with at least one logged set, which is the same rule
 *   `SessionStats.exerciseBreakdown` applies. A rail counting something else would disagree
 *   with the figure on the recap of the same workout.
 * @param total exercises in the session, laid out or added along the way.
 */
data class SessionProgress(val completed: Int = 0, val total: Int = 0) {
    init {
        require(completed >= 0) { "completed cannot be negative, was $completed" }
        require(total >= 0) { "total cannot be negative, was $total" }
        require(completed <= total) { "completed ($completed) is past total ($total)" }
    }
}

/**
 * A control on the bar: a square of [container] with [content] drawn on it.
 *
 * Its own thing rather than an `IconButton` or a `Button` because both of those take their
 * colours from the scheme, and the whole point of the bar is that it is not on the scheme. What
 * is kept is what a button owes: a 48dp touch target, a label a screen reader can read, and a
 * ripple that lands inside the shape.
 */
@Composable
private fun ChromeButton(
    onClick: () -> Unit,
    description: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    width: Dp = BUTTON_SIZE,
    body: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(width = width, height = BUTTON_SIZE)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) { body() }
    }
}

/*
 * The bar carries a clock and three controls on a 320dp screen, and the clock is the thing that
 * must not be clipped — an elapsed time reading `0:23:1` is worse than no clock at all. The
 * controls are therefore drawn at the wireframe's own 36dp rather than at a Material default,
 * and END is only as wide as the word. `Surface`'s minimum interactive size keeps all three at a
 * 48dp touch target regardless of what they are drawn at.
 */
private val BUTTON_SIZE: Dp = 36.dp
private val FINISH_WIDTH: Dp = 48.dp
private val RAIL_HEIGHT: Dp = 4.dp
private val RAIL_GAP: Dp = 4.dp

/** How much of the accent the exercise currently underway keeps. */
private const val CURRENT_ALPHA = 0.45f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Session header", widthDp = 360)
@Composable
fun SessionHeaderBarPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SessionHeaderCatalogue()
    }
}

@Preview(name = "Session header, dark", widthDp = 360)
@Composable
fun SessionHeaderBarDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        SessionHeaderCatalogue()
    }
}

@Composable
private fun SessionHeaderCatalogue() {
    Column(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        SessionHeaderBar(
            name = "Push day · Upper",
            elapsed = 23.minutes + 14.seconds,
            progress = SessionProgress(completed = 3, total = 8),
        )
        SessionHeaderBar(
            onNameClick = {},
            name = "Empty session",
            elapsed = 1.minutes + 4.seconds,
            progress = SessionProgress(completed = 0, total = 3),
            isPaused = true,
            onDiscard = {},
        )
    }
}
