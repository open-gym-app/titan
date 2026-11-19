package com.titan.core.ui.component.titan

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TitanActivity
import com.titan.core.ui.theme.LocalReducedMotion
import com.titan.core.ui.theme.TitanSpriteColors
import com.titan.core.ui.theme.TitanTheme

/**
 * The titan: one pixel sprite, "a dumbbell with a face", in the app's ink, paper and accent.
 *
 * Every pose is the same drawing ([TITAN_SPRITE]) moved in whole pixels on stepped timing
 * ([TitanPoses]); props — the barbell, the food, the water, the ghost — switch on per pose.
 *
 * ### Whole pixels only
 *
 * The sprite is 64 x 40 sprite pixels and is drawn at the largest **whole** number of device
 * pixels per sprite pixel that fits ([pixelScale]), centred on the bottom of the space it is
 * given, with no filtering. A fractional scale would smear every edge the drawing is made of.
 *
 * ### Motion
 *
 * The loop runs off the frame clock and is read **only in the draw phase**, so a pose that moves
 * redraws the canvas without recomposing anything around it. A pose change crossfades over
 * 220 ms. With reduced motion ([LocalReducedMotion]) or [still], nothing moves: each pose is its
 * readable mid-pose frame, and the change is a cut.
 *
 * @param activity what the titan is doing; the pose follows.
 * @param head the gear key worn on the head, if any. A key this build cannot draw draws nothing.
 * @param face the gear key worn on the face, if any.
 * @param ghost draws the sprite in the faint ink.
 * @param bare leaves out the ground and the water, for a host that draws its own across a wider
 *   stage — the dock does.
 * @param still holds the pose's still frame whatever the motion setting.
 * @param contentDescription what the portrait says to a screen reader; the sprite is one node.
 */
@Composable
fun TitanSprite(
    activity: TitanActivity,
    head: String?,
    face: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    ghost: Boolean = false,
    bare: Boolean = false,
    still: Boolean = false,
) {
    val reducedMotion = LocalReducedMotion.current || still
    val colors = TitanTheme.sprite
    val semantics = if (contentDescription != null) {
        Modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier.clearAndSetSemantics { }
    }

    Crossfade(
        targetState = activity,
        animationSpec = if (reducedMotion) snap() else tween(POSE_CHANGE_MILLIS),
        modifier = modifier.then(semantics),
        label = "titan pose",
    ) { pose ->
        val clock = remember { mutableLongStateOf(0L) }
        if (!reducedMotion) {
            LaunchedEffect(pose) {
                val start = withInfiniteAnimationFrameMillis { it }
                // An infinite animation's frames: a test's clock does not wait for them to settle.
                while (true) withInfiniteAnimationFrameMillis { clock.longValue = it - start }
            }
        }
        Canvas(modifier = Modifier.aspectRatio(SPRITE_WIDTH / SPRITE_HEIGHT.toFloat())) {
            val frame = TitanPoses.frame(pose, clock.longValue, still = reducedMotion)
            drawSprite(frame, colors, head = head, face = face, ghost = ghost, bare = bare)
        }
    }
}

/** The largest whole number of device pixels per sprite pixel that fits [width] by [height]. */
internal fun pixelScale(
    width: Float,
    height: Float,
): Int = minOf(width / SPRITE_WIDTH, height / SPRITE_HEIGHT).toInt().coerceAtLeast(1)

/** The gear a slot can draw, by key: the canvas draws three. */
private val HEAD_GEAR = mapOf("headband" to SpritePart.HEADBAND, "crown" to SpritePart.CROWN)
private val FACE_GEAR = mapOf("shades" to SpritePart.SHADES)
private val GEAR_PARTS = setOf(SpritePart.HEADBAND, SpritePart.CROWN, SpritePart.SHADES)

private fun DrawScope.drawSprite(
    frame: PoseFrame,
    colors: TitanSpriteColors,
    head: String?,
    face: String?,
    ghost: Boolean,
    bare: Boolean,
) {
    val unit = pixelScale(size.width, size.height).toFloat()
    val origin = Offset(
        x = ((size.width - SPRITE_WIDTH * unit) / 2f).toInt().toFloat(),
        y = (size.height - SPRITE_HEIGHT * unit).toInt().toFloat(),
    )
    val worn = setOfNotNull(head?.let(HEAD_GEAR::get), face?.let(FACE_GEAR::get))
    val hidden = frame.hidden + (GEAR_PARTS - worn)
    val ink = { color: SpriteInk -> if (ghost) ghostInk(color, colors) else colors.of(color) }

    val draw = {
        if (!bare) {
            drawRect(
                colors.ground,
                origin + Offset(0f, GROUND_Y * unit),
                Size(SPRITE_WIDTH * unit, maxOf(1f, unit / 2)),
            )
            if (frame.showDash) drawDashes(origin, unit, frame.dashShift, colors.faint)
        }
        drawNode(TITAN_SPRITE, frame, hidden, origin, unit, 0 to 0, 1f, ink)
        if (frame.showWater && !bare) drawWater(origin, unit, frame.waveShift, colors)
    }
    if (frame.clip) {
        clipRect(origin.x, origin.y, origin.x + SPRITE_WIDTH * unit, origin.y + SPRITE_HEIGHT * unit) { draw() }
    } else {
        draw()
    }
}

@Suppress("LongParameterList")
private fun DrawScope.drawNode(
    node: SpriteNode,
    frame: PoseFrame,
    hidden: Set<SpritePart>,
    origin: Offset,
    unit: Float,
    shift: Pair<Int, Int>,
    alpha: Float,
    ink: (SpriteInk) -> Color,
) {
    if (node.part in hidden) return
    val own = frame.offsets[node.part] ?: (0 to 0)
    val at = (shift.first + own.first) to (shift.second + own.second)
    val opacity = alpha * node.alpha * (frame.alphas[node.part] ?: 1f)
    if (opacity <= 0f) return

    node.pixels.forEach { pixel ->
        var y = pixel.y.toFloat()
        var h = pixel.height.toFloat()
        when (node.part) {
            // Eyes shut from the top down; the mouth chomps from the bottom up.
            SpritePart.EYE -> {
                y += h * (1f - frame.eyeScale)
                h *= frame.eyeScale
            }

            SpritePart.MOUTH -> h *= frame.mouthScale

            else -> Unit
        }
        drawRect(
            color = ink(pixel.ink),
            topLeft = origin + Offset((pixel.x + at.first) * unit, (y + at.second) * unit),
            size = Size(pixel.width * unit, h * unit),
            alpha = opacity,
        )
    }
    node.children.forEach { drawNode(it, frame, hidden, origin, unit, at, opacity, ink) }
}

private fun DrawScope.drawDashes(
    origin: Offset,
    unit: Float,
    shift: Float,
    color: Color,
) {
    var x = -DASH * 2 + shift
    while (x < SPRITE_WIDTH) {
        drawRect(color, origin + Offset(x * unit, GROUND_Y * unit), Size(DASH * unit, maxOf(1f, unit / 2)))
        x += DASH * 2
    }
}

/** The pool: a stepped surface, one pixel high, running from off the left edge. */
private fun DrawScope.drawWater(
    origin: Offset,
    unit: Float,
    shift: Int,
    colors: TitanSpriteColors,
) {
    var x = -WAVE_START + shift
    var up = false
    while (x < SPRITE_WIDTH) {
        val top = if (up) WATER_TOP - 1 else WATER_TOP
        drawRect(
            colors.water,
            origin + Offset(x * unit, top * unit),
            Size(WAVE_STEP * unit, (SPRITE_HEIGHT - top) * unit),
            WATER_ALPHA,
        )
        drawRect(colors.waterLine, origin + Offset(x * unit, top * unit), Size(WAVE_STEP * unit, maxOf(1f, unit / 2)))
        x += WAVE_STEP
        up = !up
    }
}

private fun TitanSpriteColors.of(ink: SpriteInk): Color =
    when (ink) {
        SpriteInk.K -> this.ink
        SpriteInk.W -> head
        SpriteInk.S -> headShade
        SpriteInk.G -> plate
        SpriteInk.H -> plateLight
        SpriteInk.E -> feet
        SpriteInk.D -> feetShadow
        SpriteInk.L -> feetLight
        SpriteInk.ACCENT -> spark
        SpriteInk.BAR -> bar
        SpriteInk.FAINT -> faint
        SpriteInk.GHOST -> ghost
        SpriteInk.FOOD -> food
        SpriteInk.WATER -> water
        SpriteInk.WATER_LINE -> waterLine
    }

/** The past self: outlines in the faint ink, everything else in the ghost's paper. */
private fun ghostInk(
    ink: SpriteInk,
    colors: TitanSpriteColors,
): Color = if (ink == SpriteInk.K) colors.faint else colors.ghost

private const val SPRITE_WIDTH = 64
private const val SPRITE_HEIGHT = 40
private const val GROUND_Y = 35.2f
private const val DASH = 2f
private const val WAVE_START = 8
private const val WAVE_STEP = 2
private const val WATER_TOP = 27
private const val WATER_ALPHA = 0.85f
private const val POSE_CHANGE_MILLIS = 220

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Titan sprite, every pose", widthDp = 360, heightDp = 640)
@Composable
fun TitanSpritePosesPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, reducedMotion = true) { SpriteCatalogue() }
}

@Preview(name = "Titan sprite, every pose, dark", widthDp = 360, heightDp = 640)
@Composable
fun TitanSpritePosesDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, reducedMotion = true) { SpriteCatalogue() }
}

@Composable
private fun SpriteCatalogue() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(TitanTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
        ) {
            TitanActivity.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm)) {
                    pair.forEach { activity ->
                        TitanSprite(
                            activity = activity,
                            head = if (activity == TitanActivity.TRAIN) "headband" else null,
                            face = if (activity == TitanActivity.RUN) "shades" else null,
                            contentDescription = activity.key,
                            modifier = Modifier.weight(1f).height(PREVIEW_HEIGHT.dp),
                        )
                    }
                }
            }
            TitanSprite(
                activity = TitanActivity.IDLE,
                head = "crown",
                face = null,
                ghost = true,
                contentDescription = "ghost",
                modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT.dp),
            )
        }
    }
}

private const val PREVIEW_HEIGHT = 120
