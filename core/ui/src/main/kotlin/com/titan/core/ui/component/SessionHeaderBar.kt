package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.format.formatAsElapsed
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanSessionChrome
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The bar the active workout runs under: what you are doing, how long you have been doing it,
 * and the two ways out.
 *
 * Drawn on the page's own background, in both schemes. It used to be a near-black band of its
 * own ([TitanTheme.sessionChrome]) marking the workout as a mode; on a phone the band read as a
 * separate surface fighting the page for attention, and the rest dock at the bottom of the
 * screen now does the job of saying what to do next. See [pageChrome].
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
 * The rest between two sets is not on this bar. It used to be, as a pill over the rail, and it
 * moved to [RestTimerDock] at the bottom of the screen: it is the control a lifter presses most
 * mid-rest, and the top of the screen is the band a one-handed thumb cannot reach.
 *
 * @param onClockClick manages the rest window from the clock: the tap opens the rest sheet over
 *   a rest that is going, and starts one where none is — the same as the dock, for a hand that
 *   is already up here. Null leaves the clock inert.
 * @param enabled false makes the three controls inert, for the one situation that needs it: a
 *   workout already on its way out. A finish in flight leaves the bar on screen for as long as
 *   the write takes, and a pause landing behind it either mutates a session being closed out or
 *   is refused and reported over a screen that is successfully leaving. Note that a *paused*
 *   session is not that situation — pausing is how a user comes back from it — which is why this
 *   is not [isPaused] read the other way round.
 * @param canFinish whether END in particular is live. Narrower than [enabled] and about a
 *   different wait: a set write in flight is not the workout leaving, but a finish that
 *   overtook it would land the set in a session already stamped closed. The pause and discard
 *   controls have no such race and stay live. Drawn inert rather than hidden — the control is
 *   where the user is looking, and one that vanished for a fraction of a second and came back
 *   is worse than one that does not respond for it.
 */
@Composable
fun SessionHeaderBar(
    name: String,
    elapsed: Duration,
    modifier: Modifier = Modifier,
    progress: SessionProgress = SessionProgress(),
    isPaused: Boolean = false,
    enabled: Boolean = true,
    canFinish: Boolean = true,
    onPauseToggle: () -> Unit = {},
    onFinish: () -> Unit = {},
    onDiscard: (() -> Unit)? = null,
    onNameClick: (() -> Unit)? = null,
    onClockClick: (() -> Unit)? = null,
) {
    val chrome = pageChrome()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(chrome.surface)
            .padding(horizontal = TitanTheme.spacing.screen, vertical = TitanTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // `lg`, not the `sm` the rest of the bar uses, and the width is the reason. The
            // controls are drawn at 36dp and announced at 48dp — Compose grows a tap target into
            // the room around it — but only into room nothing else holds, and a control settled
            // before its left-hand neighbour keeps the 6dp it grew into. With 8dp between them
            // the middle one is announced 42dp wide and Accessibility Scanner flags it; with 12dp
            // each of the three gets its 48.
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionTitle(
                name = name,
                elapsed = elapsed,
                isPaused = isPaused,
                onNameClick = onNameClick,
                onClockClick = onClockClick,
                modifier = Modifier.weight(1f),
            )

            if (onDiscard != null) {
                ChromeButton(
                    onClick = onDiscard,
                    description = stringResource(R.string.session_header_discard),
                    container = chrome.fill,
                    content = chrome.onSurface,
                    enabled = enabled,
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
                enabled = enabled,
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
                enabled = enabled && canFinish,
                width = FINISH_WIDTH,
            ) {
                Eyebrow(
                    text = stringResource(R.string.session_header_finish_label),
                    color = chrome.onAccent,
                    // The button says what it does — "End the session", opening with the word
                    // drawn on it, so a Voice Access user can say what they read — and an
                    // eyebrow describing itself inside it would have TalkBack read both.
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }

        if (progress.total > 0) {
            SessionProgressRail(progress = progress, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The name over the clock, and — where the caller allows it — the way to rename the workout.
 *
 * ### One node when it can be tapped
 *
 * The name is the affordance (see [SessionHeaderBar]'s `onNameClick`), and an eyebrow is 13dp
 * tall. A tap target that small is announced as whatever room it can grow into, and the clock
 * directly under it takes that room first — so the name came out 33dp tall to Accessibility
 * Scanner, an error. The rename target is therefore the *whole* title block, name and clock
 * together, which is 45dp on its own — more where the clock is a target too, below — and grows
 * the rest of the way into the bar's padding.
 *
 * Merged rather than clickable-with-children because a content description on a parent
 * *replaces* its children's text for a screen reader rather than adding to it: a plain
 * `semantics(mergeDescendants)` here would announce the clock's description and drop the name.
 * `clearAndSetSemantics` says both, in order, and re-supplies the action the clear threw away —
 * the same trap the rest dock's countdown documents.
 *
 * ### The clock inside it
 *
 * Where the caller passes `onClockClick`, the clock's line takes the touches that land on it,
 * so a finger on the digits manages the rest and the name above still renames. That line is
 * [MIN_TOUCH_TARGET] tall rather than the height of the digits, because the rename target is
 * right on top of it and would take any touch the clock was too short to. The accessibility
 * gate cannot measure it — the clear above hides the clock's node — so the screen tests do. The
 * clear also wipes the clock's tap from the accessibility tree along with everything else, so
 * it is put back on the merged node as a custom action.
 *
 * Inert, the two stay separate nodes: a name that does nothing has no business merging with a
 * clock a reader may want to find on its own.
 */
@Composable
private fun SessionTitle(
    name: String,
    elapsed: Duration,
    isPaused: Boolean,
    onNameClick: (() -> Unit)?,
    onClockClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val chrome = pageChrome()
    val clock = elapsed.formatAsElapsed()
    // The clock is announced with what it is a clock *of*; read as bare text it is four digits
    // with no noun anywhere near them.
    val spoken = stringResource(
        if (isPaused) R.string.session_elapsed_paused else R.string.session_elapsed,
        clock,
    )
    val rename = stringResource(R.string.session_header_rename)
    val merged = stringResource(R.string.session_header_title, name, spoken)
    val restWindow = stringResource(R.string.session_header_rest_window)

    Column(
        modifier = if (onNameClick != null) {
            modifier
                .clearAndSetSemantics {
                    contentDescription = merged
                    onClick(label = rename) {
                        onNameClick()
                        true
                    }
                    // The clock's own tap is cleared with the rest of the children, so a screen
                    // reader reaches it as a custom action on the block instead.
                    if (onClockClick != null) {
                        customActions = listOf(
                            CustomAccessibilityAction(restWindow) {
                                onClockClick()
                                true
                            },
                        )
                    }
                }
                .clickable(onClick = onNameClick)
        } else {
            modifier
        },
    ) {
        Eyebrow(text = name, color = chrome.onSurfaceVariant)
        Text(
            text = clock,
            style = TitanTheme.typography.numericLarge,
            color = chrome.onSurface,
            maxLines = 1,
            modifier = if (onClockClick != null) {
                // A full-height line of its own, the digits centred in it. Drawn, the clock is
                // 36dp of text, and a target that size only grows into room nothing else holds:
                // the name above is the rename target and claims every touch that lands on it,
                // so a finger a few dp high of the digits renamed the workout instead. The
                // centring slack stands in for the gap the inert clock is drawn with.
                Modifier
                    .clickable(onClickLabel = restWindow, onClick = onClockClick)
                    .heightIn(min = MIN_TOUCH_TARGET)
                    .wrapContentHeight(Alignment.CenterVertically)
            } else {
                Modifier.padding(top = TitanTheme.spacing.xs)
            }.semantics { contentDescription = spoken },
        )
    }
}

/**
 * The header's palette, taken from the colour scheme so the bar is the page it sits on.
 *
 * In the shape of [TitanSessionChrome] so every control on the bar keeps its role — the fill
 * behind a control, the accent on END and the rail — and only where the colours come from
 * changes. Every pair is a scheme pair the contrast test already holds.
 */
@Composable
private fun pageChrome(): TitanSessionChrome =
    with(MaterialTheme.colorScheme) {
        TitanSessionChrome(
            surface = background,
            onSurface = onBackground,
            onSurfaceVariant = onSurfaceVariant,
            fill = surfaceContainerHigh,
            accent = primary,
            onAccent = onPrimary,
            track = surfaceContainerHighest,
        )
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
fun SessionProgressRail(
    progress: SessionProgress,
    modifier: Modifier = Modifier,
) {
    val chrome = pageChrome()
    // The plural agrees with the total rather than with what is done: the noun belongs to
    // "of N exercises", and a workout of one exercise half finished is "0 of 1 exercise".
    val description = pluralStringResource(
        R.plurals.session_progress,
        progress.total,
        progress.completed,
        progress.total,
    )

    // The one still to do that is highest up the workout, which is where somebody who has been
    // working down it is. There is no "current exercise" on the screen this heads — every one of
    // them takes sets — so this is a reading of the workout rather than a selection in it.
    val upNext = progress.exercises.indexOfFirst { !it }

    Row(
        modifier = modifier
            .height(RAIL_HEIGHT)
            .clearAndSetSemantics {
                contentDescription = description
                // The rail fills a sliver when an exercise's last set is ticked — somewhere
                // else on the screen, without a tap on the rail. A polite live region says
                // "3 of 8 exercises done" when it happens, and is otherwise silent.
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(RAIL_GAP),
    ) {
        progress.exercises.forEachIndexed { index, isDone ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        color = when {
                            isDone -> chrome.accent

                            // The exercise being worked is the accent held back: it is
                            // underway rather than behind you, and drawing it either fully on
                            // or fully off would make the rail lie by one exercise in one
                            // direction or the other.
                            index == upNext -> chrome.accent.copy(alpha = CURRENT_ALPHA)

                            else -> chrome.track
                        },
                        shape = RoundedCornerShape(RAIL_HEIGHT / 2),
                    ),
            )
        }
    }
}

/**
 * How far through its exercises a workout is: one flag per exercise, in the order they are
 * performed.
 *
 * ### Why a flag each rather than two counts
 *
 * It was `completed` and `total`, and the rail filled its first `completed` slivers. That draws
 * the same picture for a workout done in the order it was laid out, and it stops being true the
 * moment one is reordered: a lifter who did the fourth exercise second and then dragged it up
 * has two exercises behind them that are *not* the first two, and a rail filling the first two
 * would be pointing at the wrong ones. A count cannot express that; a flag per exercise can, and
 * it costs a list of booleans as long as the workout.
 *
 * [completed] and [total] are still here, derived, because the spoken description says them and
 * a caller should not have to count.
 *
 * @param exercises whether each exercise has at least one logged set, which is the same rule
 *   `SessionStats.exerciseBreakdown` applies. A rail counting something else would disagree with
 *   the figure on the recap of the same workout.
 */
@Immutable
data class SessionProgress(val exercises: List<Boolean> = emptyList()) {

    /** Exercises with something logged against them. */
    val completed: Int get() = exercises.count { it }

    /** Exercises in the session, laid out or added along the way. */
    val total: Int get() = exercises.size

    companion object {

        /**
         * [completed] exercises done out of [total], the first ones first.
         *
         * What a workout performed in the order it was written down looks like, which is what a
         * preview or a test usually means. Anything that has an exercise-by-exercise answer
         * should give it rather than reach for this.
         */
        fun of(
            completed: Int,
            total: Int,
        ): SessionProgress {
            require(completed >= 0) { "completed cannot be negative, was $completed" }
            require(total >= 0) { "total cannot be negative, was $total" }
            require(completed <= total) { "completed ($completed) is past total ($total)" }
            return SessionProgress(List(total) { index -> index < completed })
        }
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
    enabled: Boolean = true,
    width: Dp = BUTTON_SIZE,
    body: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(width = width, height = BUTTON_SIZE)
            .semantics { contentDescription = description },
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        // Faded rather than recoloured: the bar has three tones of its own and a fourth for
        // "off" would be a colour nobody could place. What the fade has to say is that the
        // control is not there to be pressed, which is the same thing everywhere it happens.
        color = if (enabled) container else container.copy(alpha = DISABLED_ALPHA),
        contentColor = if (enabled) content else content.copy(alpha = DISABLED_ALPHA),
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

/** How much of itself a control that cannot be pressed keeps. */
private const val DISABLED_ALPHA = 0.38f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Session header", widthDp = 360, heightDp = CATALOGUE_HEIGHT_DP)
@Composable
fun SessionHeaderBarPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        SessionHeaderCatalogue()
    }
}

@Preview(name = "Session header, dark", widthDp = 360, heightDp = CATALOGUE_HEIGHT_DP)
@Composable
fun SessionHeaderBarDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        SessionHeaderCatalogue()
    }
}

@Composable
private fun SessionHeaderCatalogue() {
    Column(
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        SessionHeaderBar(
            name = "Push day · Upper",
            elapsed = 23.minutes + 14.seconds,
            progress = SessionProgress.of(completed = 3, total = 8),
        )
        SessionHeaderBar(
            onNameClick = {},
            name = "Empty session",
            elapsed = 1.minutes + 4.seconds,
            progress = SessionProgress.of(completed = 0, total = 3),
            isPaused = true,
            onDiscard = {},
        )
        // A workout done out of the order it was written down in: the second and fourth
        // exercises are behind this lifter, and the rail says so rather than filling the first
        // two slivers.
        SessionHeaderBar(
            name = "Pull day",
            elapsed = 12.minutes,
            progress = SessionProgress(listOf(false, true, false, true, false)),
        )
    }
}

/** Three bars, uncut: see the note on `PreferenceRow`'s catalogue for why a clipped one is a red test. */
private const val CATALOGUE_HEIGHT_DP = 460
