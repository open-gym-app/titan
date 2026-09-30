package com.titan.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.theme.LocalReducedMotion
import com.titan.core.ui.theme.TitanTheme
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A laurel wreath that fills leaf by leaf, with whatever the rank is written inside it.
 *
 * ### Why not the ring
 *
 * This replaced [com.titan.core.ui.chart.ProgressRing] on the quest log, and the reason is not
 * decoration. A ring is a *measurement* — it is the shape this app draws a proportion in, on the
 * stats hub and in half a dozen charts — and a rank is not a proportion. Drawing the two the same
 * way made the level read as one more figure on a screen full of figures. A wreath is an award:
 * it is the oldest visual grammar there is for "you earned this", it is unmistakably not a chart,
 * and it still carries the progress honestly because the leaves fill as the XP does.
 *
 * ### The leaves fill from the bottom, both sides at once
 *
 * Each branch grows from the base of the wreath upward, and the two advance together — so the
 * shape stays symmetrical at every value, and "nearly a full level" reads as a wreath that has
 * nearly closed at the top. Filling one branch and then the other would be a progress bar bent
 * into a circle, which is the thing this is not.
 *
 * The boundary leaf carries the remainder as opacity, so the wreath moves on every point of XP
 * rather than in [LEAVES_PER_BRANCH] steps. Without it a user earning twenty points would look at
 * an unchanged wreath and conclude nothing had happened.
 *
 * ### It grows in once, and lands immediately under reduced motion
 *
 * The fill animates from zero on first composition — the wreath assembling itself is the moment
 * worth having — and is held across configuration changes so a rotation does not replay it. Under
 * reduced motion it is drawn at its value with no animation at all, the contract every animation
 * in this codebase is under. See [LocalReducedMotion].
 *
 * @param progress how far into the level, `0f..1f`. Coerced, so a caller that computes 1.02 from
 *   a curve draws a closed wreath rather than an over-drawn one.
 * @param label what the wreath is about — "Level 14". Spoken, never drawn: the caller draws the
 *   figure itself through [content].
 * @param valueLabel the figures behind it — "30 / 100 XP". Spoken.
 * @param content what sits at the centre. Usually the level, as a numeral.
 */
@Composable
fun LaurelWreath(
    progress: Float,
    label: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    size: Dp = LAUREL_SIZE,
    content: @Composable BoxScope.() -> Unit,
) {
    val fraction = progress.coerceIn(0f, 1f)
    val spoken = stringResource(
        R.string.laurel_wreath_description,
        label,
        valueLabel,
        (fraction * PERCENT).roundToInt(),
    )

    val reducedMotion = LocalReducedMotion.current

    // Zero until the first frame, then the real value — the shape `ConfettiBurst` and
    // `CountUpNumber` both use, and for the same reason: an animation seeded at its own
    // destination never runs. Saveable, so a rotation does not replay the growth.
    var target by rememberSaveable { mutableFloatStateOf(if (reducedMotion) fraction else 0f) }
    LaunchedEffect(fraction) { target = fraction }

    val grown by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else GROWTH_MILLIS),
        label = "laurel",
    )

    val earned = MaterialTheme.colorScheme.primary
    val pending = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            drawBranch(mirrored = false, fraction = grown, earned = earned, pending = pending)
            drawBranch(mirrored = true, fraction = grown, earned = earned, pending = pending)
        }
        content()
    }
}

/**
 * One side of the wreath: [LEAVES_PER_BRANCH] leaves set along an arc.
 *
 * There is no stem. A first version drew one under the leaves and it read as a progress ring with
 * decoration on it — which is precisely what this component exists not to be. The leaves alone
 * describe the arc, the way a real wreath's do.
 *
 * [mirrored] flips the branch across the vertical axis. Mirroring rather than a second set of
 * angles because the two halves must be exact reflections — a wreath whose sides disagree by two
 * degrees reads as a mistake long before a reader can say why.
 */
private fun DrawScope.drawBranch(
    mirrored: Boolean,
    fraction: Float,
    earned: Color,
    pending: Color,
) {
    val centre = Offset(x = size.width / 2f, y = size.height / 2f)
    val radius = size.minDimension * RADIUS_SHARE
    val leafLength = size.minDimension * LEAF_LENGTH_SHARE
    val leafWidth = size.minDimension * LEAF_WIDTH_SHARE
    val direction = if (mirrored) -1f else 1f

    repeat(LEAVES_PER_BRANCH) { index ->
        // Along the branch from its base at the bottom to its tip near the top.
        val t = index.toFloat() / (LEAVES_PER_BRANCH - 1)
        val degrees = BASE_DEGREES + (TIP_DEGREES - BASE_DEGREES) * t
        val radians = Math.toRadians(degrees.toDouble())

        // Angles run clockwise from twelve o'clock, so the base sits at the bottom of the
        // wreath and the tip near its top — the direction a laurel is worn.
        val position = Offset(
            x = centre.x + direction * radius * sin(radians).toFloat(),
            y = centre.y - radius * cos(radians).toFloat(),
        )

        // The leaf is earned once the fill has passed its share of the branch. The one on the
        // boundary carries the remainder as opacity; see the class KDoc.
        val share = (index + 1).toFloat() / LEAVES_PER_BRANCH
        val previousShare = index.toFloat() / LEAVES_PER_BRANCH
        val filled = ((fraction - previousShare) / (share - previousShare)).coerceIn(0f, 1f)
        val colour = if (filled > 0f) earned else pending
        val alpha = if (filled > 0f) FAINT_ALPHA + (1f - FAINT_ALPHA) * filled else 1f

        // Leaves sweep away from the stem rather than lying along it, which is what stops the
        // branch reading as a dashed arc.
        val tilt = direction * (degrees + LEAF_SWEEP_DEGREES)
        rotate(degrees = tilt, pivot = position) {
            drawOval(
                color = colour,
                alpha = alpha,
                topLeft = Offset(position.x - leafWidth / 2f, position.y - leafLength / 2f),
                size = Size(width = leafWidth, height = leafLength),
            )
        }
    }
}

/** The wreath at the top of the quest log. Large: it is the one thing the eye lands on. */
val LAUREL_SIZE: Dp = 168.dp

/** Leaves per side. Nine is enough that the fill reads as continuous and few enough to draw. */
private const val LEAVES_PER_BRANCH = 9

/** Where a branch starts, in degrees clockwise from twelve o'clock. Just off the bottom. */
private const val BASE_DEGREES = 160f

/** Where it ends. Short of the top, so the two branches leave a gap a wreath has. */
private const val TIP_DEGREES = 28f

/** How far a leaf sweeps off the stem. */
private const val LEAF_SWEEP_DEGREES = 18f

/** The wreath's radius, as a share of the box. */
private const val RADIUS_SHARE = 0.36f

private const val LEAF_LENGTH_SHARE = 0.15f
private const val LEAF_WIDTH_SHARE = 0.062f

/** How visible an unfinished leaf's earned tint is at the moment it starts filling. */
private const val FAINT_ALPHA = 0.25f

private const val GROWTH_MILLIS = 900

private const val PERCENT = 100

/** Empty, part-way and closed — the three shapes a reviewer needs to see side by side. */
private val CATALOGUE_VALUES = listOf(0f, 0.45f, 1f)

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Laurel wreath")
@Composable
fun LaurelWreathPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        LaurelWreathCatalogue()
    }
}

@Preview(name = "Laurel wreath - dark")
@Composable
fun LaurelWreathDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        LaurelWreathCatalogue()
    }
}

@Composable
private fun LaurelWreathCatalogue() {
    androidx.compose.foundation.layout.Row {
        CATALOGUE_VALUES.forEach { value ->
            LaurelWreath(
                progress = value,
                label = "Level 14",
                valueLabel = "30 / 100 XP",
                size = 120.dp,
            ) {
                androidx.compose.material3.Text(
                    text = "14",
                    style = TitanTheme.typography.numericLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
