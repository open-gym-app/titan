package com.opengym.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far through a numbered flow the user is, as one filled rail.
 *
 * The wireframes' onboarding treatment: a 3dp track with the accent filling it left to right,
 * under the `STEP 2 OF 4` eyebrow that names the same fact in words. Deliberately not
 * Material's `LinearProgressIndicator`, which is about *work in progress* — it has a stop
 * indicator, an indeterminate mode and a gap between track and bar, all of which are answers
 * to a question a step counter is not asking.
 *
 * **It is decorative, and says so.** The semantics are cleared, because the number of steps
 * and the one being shown is something the caller has already put on screen as text — a rail
 * that also announced "step 2 of 4" would have a screen reader read the same sentence twice.
 * A caller that draws this *without* a label beside it has hidden the progress from half its
 * users and should add the label rather than the semantics.
 *
 * @param step which step is showing, counting from one. Clamped, so a caller that has an
 *   off-by-one draws a full or an empty rail rather than one that overshoots its track.
 * @param steps how many there are in total. Must be positive.
 * @param thickness the rail's height. The default is the wireframes' 3dp; it is a parameter
 *   only so a denser host — a card header rather than a screen — can ask for less.
 */
@Composable
fun StepProgress(
    step: Int,
    steps: Int,
    modifier: Modifier = Modifier,
    thickness: Dp = STEP_PROGRESS_THICKNESS,
) {
    require(steps > 0) { "a flow has at least one step, was $steps" }

    // Clamped, not trusted: `fillMaxWidth` rejects a fraction outside 0..1, so an off-by-one
    // in a caller would be a crash rather than a rail that looks a step wrong.
    val fraction = step.coerceIn(0, steps).toFloat() / steps
    // Animated, because the rail is the only thing on the screen that moves when Continue is
    // pressed and the next step's content is swapped in under it. Snapping would read as the
    // header being redrawn rather than as progress being made.
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        label = "step progress",
    )

    Box(
        modifier = modifier
            .clearAndSetSemantics {}
            .fillMaxWidth()
            .height(thickness)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraSmall,
            ),
    ) {
        Box(
            modifier = Modifier
                // Coerced as well as clamped above: `fillMaxWidth` rejects a fraction outside
                // 0..1, and an animation landing on 1.0000001 is not a reason to crash a screen.
                .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        )
    }
}

/**
 * The rail's drawn weight.
 *
 * Three device-independent pixels: heavier than a hairline, because this one is *content*
 * rather than a rule, and still thin enough that it reads as a measure rather than as a bar
 * competing with the heading under it.
 */
val STEP_PROGRESS_THICKNESS: Dp = 3.dp
