package com.opengym.core.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.theme.LocalReducedMotion

/**
 * How far into the current level the user is, as a bar that fills on first draw.
 *
 * ### Why a bar and not a ring
 *
 * `ProgressRing` already exists and is what the stats hub uses for a *proportion of a goal*.
 * This is a different question — distance along a track that has no end, since there is always
 * another level — and a bar is the shape that says "further along" rather than "closer to
 * done". They are deliberately not the same component.
 *
 * ### It fills from empty, once
 *
 * Same contract as [CountUpNumber] and [ConfettiBurst]: the fill is animated on the first
 * composition, held across configuration changes, and lands immediately under
 * [LocalReducedMotion]. The three run on one screen and have to agree about whether the moment
 * is being replayed.
 *
 * ### A level-up rewinds rather than fills backwards
 *
 * When the session crossed a boundary the bar is drawn against the **new** level, so it starts
 * near empty and fills a short way. It does not run to the end and snap back to zero: that
 * animation reads as losing the level, which is the opposite of what happened. The crossing is
 * announced by the banner above it; this bar's only job is where the user stands now.
 *
 * @param progress how far through the level, `0f..1f`. Clamped rather than trusted — a caller
 *   that computed it from an XP total and a threshold can land a hair outside on a rounding.
 * @param contentDescription what a screen reader says. Required rather than derived: the
 *   sentence needs the level number and the points remaining, and neither is in this component.
 * @param color the fill.
 * @param trackColor the remainder.
 */
@Composable
fun LevelProgressBar(
    progress: Float,
    contentDescription: String,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val end = progress.coerceIn(0f, 1f)

    var target by rememberSaveable { mutableFloatStateOf(if (reducedMotion) end else 0f) }
    LaunchedEffect(end, reducedMotion) { target = end }

    val filled by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else FILL_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        label = "level-progress",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)

        // Nothing is drawn at zero: a bar with a stub of fill on it claims progress that has
        // not been made, and the rounded cap makes even a hairline read as a few points.
        if (filled <= 0f) return@Canvas
        drawRoundRect(
            color = color,
            topLeft = Offset.Zero,
            // Never narrower than its own cap, so the first points into a level are visible
            // as a dot rather than as nothing at all.
            size = Size(
                width = (size.width * filled).coerceAtLeast(size.height),
                height = size.height,
            ),
            cornerRadius = radius,
        )
    }
}

/** How long the fill takes. Shorter than the XP count above it, so the two do not race. */
private const val FILL_MILLIS = 700

private val BAR_HEIGHT = 10.dp
