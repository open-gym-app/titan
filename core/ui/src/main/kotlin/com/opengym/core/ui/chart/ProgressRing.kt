package com.opengym.core.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.theme.OpenGymTheme
import kotlin.math.roundToInt

/**
 * A fraction as a ring: a track, an accent arc, and whatever the caller puts in the middle.
 *
 * ```text
 *      ╭──────╮
 *    ╱   LVL    ╲
 *   │    14      │
 *    ╲          ╱
 *      ╰────
 * ```
 *
 * The quest log's XP gauge (#104). A ring rather than a bar because it is the one place the
 * app draws a *level* — a bar says "this much of a task", a ring says "this much of a rank" —
 * and because the figure inside it is the thing being read, with the arc as its frame.
 *
 * It is not a general progress indicator. Material's `CircularProgressIndicator` is what a
 * screen shows while it waits, announces itself as busy, and spins; this is a readout of a
 * settled value. Reaching for the Material one here would tell a screen reader the screen was
 * loading every time the user's XP was on it.
 *
 * ### One accent, spent on the arc
 *
 * The track is `surfaceContainerHighest` and the arc is `primary`, matching
 * [BarSeriesChart]'s pairing so a ring and a chart on the same screen read as one family. The
 * content in the middle takes its own colours from the caller, which is what lets the figure
 * be the accent on a screen where the ring is the point and neutral on one where it is not.
 *
 * ### Announced as one node
 *
 * The label, the value and the percentage as one sentence, with the drawn content cleared —
 * `LVL` and `14` as two nodes are two swipes for one fact, and the arc has nothing to say on
 * its own. The same treatment the charts give their canvases.
 *
 * @param progress how far round the arc goes, `0f..1f`. Clamped rather than validated: a
 *   caller whose numerator has overtaken its denominator (an XP award landing before the
 *   level-up is written) should draw a full ring, not crash or wrap past the top.
 * @param label what the ring is measuring, in the caller's words: `Level 14`.
 * @param valueLabel the fraction as the user reads it: `2,140 of 3,000 XP`. Formatted by the
 *   caller — see [ChartEntry] for why nothing here reads a unit system.
 * @param content the figure inside. Usually an [Eyebrow] over a numeric role.
 */
@Composable
fun ProgressRing(
    progress: Float,
    label: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = ChartDefaults.RING_SIZE,
    content: @Composable () -> Unit,
) {
    val fraction = progress.coerceIn(0f, 1f)
    val spoken = stringResource(
        R.string.progress_ring_description,
        label,
        valueLabel,
        (fraction * PERCENT).roundToInt(),
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arc = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val width = ChartDefaults.RING_WIDTH.toPx()
            val stroke = Stroke(width = width, cap = StrokeCap.Round)
            // Inset by half the stroke on every side: an arc is centred on its path, so a ring
            // drawn to the canvas's edge loses its outer half to the clip.
            val topLeft = Offset(width / 2, width / 2)
            val arcSize = Size(this.size.width - width, this.size.height - width)

            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = FULL_TURN,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            // From the top, clockwise — where a gauge starts, and the direction a level fills.
            drawArc(
                color = arc,
                startAngle = START_ANGLE,
                sweepAngle = FULL_TURN * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }
        content()
    }
}

/** Twelve o'clock, in the coordinates `drawArc` measures from (zero being three o'clock). */
private const val START_ANGLE = -90f

private const val FULL_TURN = 360f

private const val PERCENT = 100f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Progress ring", widthDp = 360)
@Composable
fun ProgressRingPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ProgressRingCatalogue()
    }
}

@Preview(name = "Progress ring, dark", widthDp = 360)
@Composable
fun ProgressRingDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ProgressRingCatalogue()
    }
}

@Composable
private fun ProgressRingCatalogue() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        listOf(0f, PREVIEW_PART_WAY, 1f).forEach { progress ->
            ProgressRing(
                progress = progress,
                label = "Level 14",
                valueLabel = "2,140 of 3,000 XP",
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow(text = "Lvl")
                    Text(
                        text = "14",
                        style = OpenGymTheme.typography.numericLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private const val PREVIEW_PART_WAY = 0.71f
