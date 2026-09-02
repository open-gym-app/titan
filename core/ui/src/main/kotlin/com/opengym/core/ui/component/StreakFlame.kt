package com.opengym.core.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.LocalReducedMotion
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A flame that flickers, for a streak that is alive.
 *
 * ### Why a streak gets the one perpetual animation in the app
 *
 * Everything else that moves here plays once and stops: a count runs up, a bar fills, confetti
 * falls. This does not stop, and that is the point — a streak is the one figure on the screen
 * that is *currently at risk*. It is true only until tomorrow. A still icon says "you have twelve
 * days"; a flame that is still burning says "you have twelve days and they are still going", which
 * is the fact the user is actually being asked to act on.
 *
 * It earns the exception by being small, by never moving the layout — the flicker is a scale
 * around a fixed box, so nothing around it reflows — and by being drawn only when there *is* a
 * streak. A zero-day streak draws no flame at all: a dead flame beside a zero would be the app
 * illustrating a failure.
 *
 * ### Under reduced motion it simply stands still
 *
 * Not a slower flicker: a user who has asked the system for no animation is asking for a picture,
 * and this becomes one. The flame is still drawn, because the *shape* carries the meaning and only
 * the movement is decoration. See [LocalReducedMotion].
 *
 * **It says nothing.** No content description: the row it sits in already speaks the streak as one
 * sentence, and "flame" read out before "12 days" is a decoration announcing itself.
 */
@Composable
fun StreakFlame(
    modifier: Modifier = Modifier,
    size: Dp = STREAK_FLAME_SIZE,
    color: Color = OpenGymTheme.celebration.xp,
    coreColor: Color = OpenGymTheme.celebration.levelUpEdge,
) {
    val reducedMotion = LocalReducedMotion.current

    // Two beats at slightly different periods, so the flame never repeats a shape it has just
    // shown. One would read as a pulse; two read as a flicker.
    val transition = rememberInfiniteTransition(label = "streak flame")
    val stretch by transition.animateFloat(
        initialValue = 1f,
        targetValue = STRETCH_PEAK,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = STRETCH_MILLIS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stretch",
    )
    val lean by transition.animateFloat(
        initialValue = -LEAN_PEAK,
        targetValue = LEAN_PEAK,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LEAN_MILLIS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lean",
    )

    val scaleY = if (reducedMotion) 1f else stretch
    val skew = if (reducedMotion) 0f else lean

    Canvas(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { },
    ) {
        // Scaled about the *base*, so the flame grows upward from where it is anchored rather
        // than about its middle — a fire does not shrink into the ground.
        // `this.size`, not `size` — the composable's own `size: Dp` parameter shadows the
        // draw scope's inside this lambda, and the two are different units.
        val box = this.size
        scale(scaleX = 1f, scaleY = scaleY, pivot = Offset(box.width / 2f, box.height)) {
            drawFlame(color = color, lean = skew, share = 1f)
            drawFlame(color = coreColor, lean = skew * CORE_LEAN_SHARE, share = CORE_SHARE)
        }
    }
}

/**
 * One teardrop, [share] of the canvas tall, leaning by [lean] of its own width at the tip.
 *
 * A path rather than a vector asset: the shape is four curves and it has to be tinted from the
 * theme at two different scales, which an imported drawable makes harder rather than easier.
 */
private fun DrawScope.drawFlame(
    color: Color,
    lean: Float,
    share: Float,
) {
    val width = size.width * share
    val height = size.height * share
    val left = (size.width - width) / 2f
    val bottom = size.height
    val top = bottom - height
    val tipX = left + width / 2f + lean * width

    val path = Path().apply {
        moveTo(left + width / 2f, bottom)
        // The right shoulder, bellying out low and drawing in to the tip.
        cubicTo(
            x1 = left + width * SHOULDER,
            y1 = bottom - height * BELLY,
            x2 = left + width,
            y2 = top + height * WAIST,
            x3 = tipX,
            y3 = top,
        )
        // The left shoulder, back down to the base.
        cubicTo(
            x1 = left,
            y1 = top + height * WAIST,
            x2 = left + width * (1f - SHOULDER),
            y2 = bottom - height * BELLY,
            x3 = left + width / 2f,
            y3 = bottom,
        )
        close()
    }
    drawPath(path = path, color = color)
}

/** The flame beside a streak count. Sized to a line of the numeric-large role. */
val STREAK_FLAME_SIZE: Dp = 28.dp

/** The brighter heart of the flame, as a share of the whole. */
private const val CORE_SHARE = 0.5f

/** The core leans less than the outer flame, which is what gives the two a sense of depth. */
private const val CORE_LEAN_SHARE = 0.6f

private const val STRETCH_PEAK = 1.16f
private const val LEAN_PEAK = 0.09f
private const val STRETCH_MILLIS = 620
private const val LEAN_MILLIS = 970

/** How far out the flame bellies, and where it draws back in. Shares of its own box. */
private const val SHOULDER = 1.05f
private const val BELLY = 0.15f
private const val WAIST = 0.55f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Streak flame")
@Composable
fun StreakFlamePreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        StreakFlame(size = 64.dp)
    }
}

@Preview(name = "Streak flame - dark")
@Composable
fun StreakFlameDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        StreakFlame(size = 64.dp)
    }
}
