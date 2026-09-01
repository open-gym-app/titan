package com.opengym.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.opengym.core.ui.theme.LocalReducedMotion
import kotlin.math.sin
import kotlin.random.Random

/**
 * A one-shot scatter of falling pieces, drawn behind whatever it is celebrating.
 *
 * ### It plays once and stops
 *
 * Not a loop. Confetti that keeps falling stops being an event and becomes wallpaper, and it
 * would still be animating under a user who has sat on the recap writing a note. The progress
 * is held across configuration changes for the same reason [CountUpNumber]'s is: a rotation
 * must not re-throw it.
 *
 * ### Reduced motion draws nothing
 *
 * Nothing, rather than a still scatter. Every other degradation in this app renders the same
 * information without the movement, because the information is the point — but confetti has no
 * information in it. A frozen pile of shapes over a recap is a decoration a user who asked for
 * stillness did not ask for either, and the screen reads correctly without it. See
 * [LocalReducedMotion].
 *
 * ### Invisible to screen readers
 *
 * The semantics are cleared: there is nothing here to announce, and a canvas that reported
 * itself would put a meaningless node between the level-up banner and the XP figure.
 *
 * @param colors the palette pieces are cycled through — `OpenGymTheme.celebration.confetti`.
 *   Passed rather than read, so this composable stays a drawing primitive with no opinion about
 *   which moment is being celebrated.
 * @param pieceCount how many pieces fall. Tuned by eye rather than derived: enough to read as a
 *   burst, few enough that they do not obscure the figure underneath.
 * @param seed fixes the scatter. Defaulted to a value drawn once per composition, and pinned by
 *   screenshot tests so a reference image does not change on every run.
 */
@Composable
fun ConfettiBurst(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    pieceCount: Int = DEFAULT_PIECES,
    seed: Int = remember { Random.nextInt() },
) {
    if (LocalReducedMotion.current || colors.isEmpty()) return

    // Zero until the first frame, then raised to one — the same shape as [CountUpNumber]'s
    // target, and for the same reason: an animation seeded at its own destination never runs.
    var target by rememberSaveable { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { target = 1f }

    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = FALL_MILLIS, easing = LinearEasing),
        label = "confetti",
    )

    // The pieces' fixed properties, drawn once from the seed. Held in a `remember` rather than
    // recomputed per frame: sixty allocations a second of the same forty values is the kind of
    // thing that makes a celebration drop frames on the phone it matters on.
    val pieces = remember(seed, pieceCount) { confettiPieces(seed, pieceCount) }

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        pieces.forEachIndexed { index, piece ->
            // Each piece starts and finishes at its own moment, so they do not fall as a
            // curtain. The clamp is what lets a late piece still complete inside the window.
            val local = ((progress - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed

            val x = size.width * piece.x + sin(local * piece.sway * TAU) * SWAY_PIXELS
            val y = size.height * local * piece.fall

            drawRect(
                color = colors[index.mod(colors.size)],
                topLeft = Offset(x, y),
                size = Size(piece.width, piece.height),
                // Fades out over the last third rather than vanishing on the final frame.
                alpha = ((1f - local) * FADE_GAIN).coerceIn(0f, 1f),
            )
        }
    }
}

/** One piece's fixed properties: where it starts, how fast it falls and how much it wanders. */
private data class ConfettiPiece(
    val x: Float,
    val delay: Float,
    val fall: Float,
    val sway: Float,
    val width: Float,
    val height: Float,
)

private fun confettiPieces(
    seed: Int,
    count: Int,
): List<ConfettiPiece> {
    val random = Random(seed)
    return List(count) {
        ConfettiPiece(
            x = random.nextFloat(),
            delay = random.nextFloat() * MAX_DELAY,
            fall = MIN_FALL + random.nextFloat() * (1f - MIN_FALL),
            sway = MIN_SWAY + random.nextFloat() * (MAX_SWAY - MIN_SWAY),
            width = MIN_PIECE + random.nextFloat() * (MAX_PIECE - MIN_PIECE),
            height = MIN_PIECE + random.nextFloat() * (MAX_PIECE - MIN_PIECE),
        )
    }
}

private const val DEFAULT_PIECES = 40

/** How long the whole burst takes, from the first piece leaving to the last fading out. */
private const val FALL_MILLIS = 1_800

/** The latest a piece may start, as a fraction of the burst — what staggers the fall. */
private const val MAX_DELAY = 0.35f

/** The shallowest fall, as a fraction of the canvas: no piece stops in the top half. */
private const val MIN_FALL = 0.6f

private const val MIN_SWAY = 0.5f
private const val MAX_SWAY = 2.5f
private const val SWAY_PIXELS = 14f

private const val MIN_PIECE = 6f
private const val MAX_PIECE = 13f

/** Holds a piece at full opacity for the first stretch, so the burst does not open faded. */
private const val FADE_GAIN = 2.2f

private const val TAU = 6.2831855f
