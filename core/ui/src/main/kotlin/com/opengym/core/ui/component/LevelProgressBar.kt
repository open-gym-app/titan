package com.opengym.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * How far into the current level the user is.
 *
 * ### Why a bar and not a ring
 *
 * `ProgressRing` already exists and is what the stats hub uses for a *proportion of a goal*. This
 * is a different question — distance along a track that has no end, since there is always another
 * level — and a bar is the shape that says "further along" rather than "closer to done". They are
 * deliberately not the same component.
 *
 * ### It draws; it does not animate
 *
 * [progress] is a lambda, and the movement belongs to whoever supplies it — see
 * [rememberLevelSweep], which sweeps the bar through every threshold a session crossed. Two
 * reasons the animation is not in here. A bar that animated toward whatever it was last handed
 * could not express a *reset*: the sweep drops from full back to empty at each crossing, and an
 * internal animation would gently run backwards through the level the user just gained, which
 * reads as losing it. And reading the fraction inside the draw scope means a sixty-hertz
 * animation repaints without recomposing anything.
 *
 * @param progress how far through the level, `0f..1f`. Clamped rather than trusted — a caller
 *   that computed it from an XP total and a threshold can land a hair outside on a rounding.
 * @param contentDescription what a screen reader says. Required rather than derived: the sentence
 *   needs the level number and the points remaining, and neither is in this component.
 */
@Composable
fun LevelProgressBar(
    progress: () -> Float,
    contentDescription: String,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)

        val filled = progress().coerceIn(0f, 1f)
        // Nothing is drawn at zero: a bar with a stub of fill on it claims progress that has not
        // been made, and the rounded cap makes even a hairline read as a few points.
        if (filled <= 0f) return@Canvas

        drawRoundRect(
            color = color,
            topLeft = Offset.Zero,
            // Never narrower than its own cap, so the first points into a level are visible as a
            // dot rather than as nothing at all.
            size = Size(
                width = (size.width * filled).coerceAtLeast(size.height),
                height = size.height,
            ),
            cornerRadius = radius,
        )
    }
}

private val BAR_HEIGHT = 10.dp
