package com.titan.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.ui.theme.TitanTheme

/**
 * How far through a numbered flow the user is, as one segment per step.
 *
 * **Segments rather than one filled bar.** A continuous rail answers "how much is left" as a
 * proportion, which is the question a download asks; a numbered flow is asking "which of the
 * four am I on", and four separated ticks say that at a glance without the user reading the
 * counter beside them. It is also the difference between a step that *lands* — one more tick
 * takes the accent — and a bar that slides to a position nobody can measure.
 *
 * Deliberately not Material's `LinearProgressIndicator`, which is about *work in progress*: it
 * has a stop indicator, an indeterminate mode and a track gap, all of which are answers to a
 * question a step counter is not asking.
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
 * @param thickness a segment's height. The default is the wireframes' 3dp; it is a parameter
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

    // Clamped, not trusted: an off-by-one in a caller should draw a full or an empty rail
    // rather than crash a screen on a negative repeat count.
    val reached = step.coerceIn(0, steps)

    Row(
        modifier = modifier.clearAndSetSemantics {}.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        repeat(steps) { index ->
            // The colour is animated rather than snapped, because the rail is the only thing
            // on the screen that stays put while the step under it is replaced. A tick that
            // simply appears reads as the header being redrawn; one that fills reads as
            // progress being made.
            val color by animateColorAsState(
                targetValue = if (index < reached) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                label = "step ${index + 1}",
            )

            Box(
                modifier = Modifier
                    // Equal shares of the width, so the rail reads as a measure of the flow
                    // rather than as four items that happen to be in a row.
                    .weight(1f)
                    .height(thickness)
                    .background(color = color, shape = MaterialTheme.shapes.extraSmall),
            )
        }
    }
}

/**
 * A segment's drawn weight.
 *
 * Three device-independent pixels: heavier than a hairline, because this one is *content*
 * rather than a rule, and still thin enough that it reads as a measure rather than as a bar
 * competing with the heading under it.
 */
val STEP_PROGRESS_THICKNESS: Dp = 3.dp
