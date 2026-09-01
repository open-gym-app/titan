package com.opengym.core.ui.component

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import com.opengym.core.ui.theme.LocalReducedMotion

/**
 * A whole number that counts up to [value] the first time it is drawn.
 *
 * The tally is the point rather than the animation: a figure that lands instantly is read as a
 * fact about the session, and one that runs up to itself is read as *being earned*. That is the
 * whole difference between a recap and a payoff, and it costs one animation.
 *
 * ### It runs once, not on every recomposition
 *
 * The starting point is remembered across configuration changes, so a rotation mid-count does
 * not restart it and a rotation after it does not replay it. A screen that re-counted its XP
 * every time the user turned the phone would be claiming the points were earned again.
 *
 * ### Reduced motion lands it immediately
 *
 * Not "faster" — immediately. [LocalReducedMotion] is the user having asked for no animation,
 * and a shorter animation is still an animation. The figure is identical either way, which is
 * what makes the degradation honest: nothing is communicated by the count that is not also
 * communicated by the number it stops on.
 *
 * ### One label, whatever the digits are doing
 *
 * The semantics are cleared and replaced with [contentDescription], so a screen reader
 * announces the final figure once instead of narrating an integer that changes sixty times a
 * second.
 *
 * @param value what to count to. Counting *down* is not a case this handles: it is only ever
 *   pointed at a total that was just earned.
 * @param contentDescription what a screen reader says instead of the running digits.
 * @param format how the figure is rendered — the caller's, so a locale-aware formatter stays
 *   in `com.opengym.core.ui.format` rather than being re-implemented here.
 */
@Composable
fun CountUpNumber(
    value: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    format: (Int) -> String = { it.toString() },
) {
    val reducedMotion = LocalReducedMotion.current

    // What the animation is *aiming* at, which is zero until the first frame has been drawn.
    // `animateIntAsState` seeds itself from the value it is first composed with, so a target
    // that is already [value] produces no animation at all — the count has to start below it
    // and be raised.
    //
    // `rememberSaveable` is what makes it run exactly once: after a rotation the saved target
    // is already [value], the animation seeds there, and the figure is simply drawn. A screen
    // that re-counted its XP every time the user turned the phone would be claiming the points
    // had been earned again.
    var target by rememberSaveable { mutableIntStateOf(if (reducedMotion) value else 0) }
    LaunchedEffect(value, reducedMotion) { target = value }

    val shown by animateIntAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else COUNT_MILLIS,
            easing = LinearOutSlowInEasing,
        ),
        label = "xp-count",
    )

    Text(
        text = format(shown),
        style = style,
        color = color,
        maxLines = 1,
        // The digits do not reflow as they run: the numeric roles carry tabular figures — see
        // `Type.kt` — so every digit occupies the same advance and the string only widens when
        // it gains one.
        modifier = modifier.clearAndSetSemantics { this.contentDescription = contentDescription },
    )
}

/**
 * How long the count takes.
 *
 * Long enough to read as a tally being run up, short enough that a user reaching for Done is
 * not waiting on it — and it is never *blocking*, since the number is already correct to a
 * screen reader from the first frame.
 */
private const val COUNT_MILLIS = 900
