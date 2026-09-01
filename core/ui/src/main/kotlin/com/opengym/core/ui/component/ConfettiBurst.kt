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
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A one-shot scatter of pieces, drawn behind whatever it is celebrating.
 *
 * ### Two motions, because two things are being celebrated
 *
 * [ConfettiStyle] is not a decoration setting. An ordinary level and a milestone are different
 * events, and making the second one *louder* — more pieces, a harder kick — only ever reads as
 * the same event with the volume turned up. What separates them is the shape of the movement: one
 * **falls**, the other **detonates**. See [ConfettiStyle], and `LevelCeremony`, which decides
 * which one a crossing gets.
 *
 * ### It plays once and stops
 *
 * Not a loop. Confetti that keeps falling stops being an event and becomes wallpaper, and it
 * would still be animating under a user who has sat on the recap writing a note. The progress is
 * held across configuration changes for the same reason [CountUpNumber]'s is: a rotation must not
 * re-throw it.
 *
 * ### It draws into whatever size it is given, so it has to be given one
 *
 * Every position here is a fraction of the canvas, which means a canvas of no height is a burst
 * of no movement — every piece lands on one edge and the effect reads as a row of stuck dots.
 * Inside a `Box` that is sized by another child, and under an unbounded height constraint such as
 * a `LazyColumn` item's, `fillMaxSize` measures to nothing and does precisely that.
 * `Modifier.matchParentSize()` is the one that works there: sized from the parent after the fact,
 * and not part of what decides the parent's size.
 *
 * ### Reduced motion draws nothing
 *
 * Nothing, rather than a still scatter. Every other degradation in this app renders the same
 * information without the movement, because the information is the point — but confetti has no
 * information in it. A frozen pile of shapes over a recap is a decoration a user who asked for
 * stillness did not ask for either, and the screen reads correctly without it.
 *
 * ### Invisible to screen readers
 *
 * The semantics are cleared: there is nothing here to announce, and a canvas that reported itself
 * would put a meaningless node between the level-up banner and the XP figure.
 *
 * @param colors the palette pieces are cycled through — `OpenGymTheme.celebration.confetti`.
 *   Passed rather than read, so this composable stays a drawing primitive with no opinion about
 *   which moment is being celebrated.
 * @param style how the pieces move. See [ConfettiStyle].
 * @param pieceCount how many pieces. Tuned by eye rather than derived: enough to read as a burst,
 *   few enough that they do not obscure the figure underneath.
 * @param seed fixes the scatter. Defaulted to a value drawn once per composition, and pinned by
 *   screenshot tests so a reference image does not change on every run.
 */
@Composable
fun ConfettiBurst(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    style: ConfettiStyle = ConfettiStyle.RAIN,
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
        animationSpec = tween(durationMillis = style.durationMillis, easing = LinearEasing),
        label = "confetti",
    )

    // The pieces' fixed properties, drawn once from the seed. Held in a `remember` rather than
    // recomputed per frame: sixty allocations a second of the same values is the kind of thing
    // that makes a celebration drop frames on the phone it matters on.
    val pieces = remember(seed, pieceCount, style) { confettiPieces(seed, pieceCount, style) }

    Canvas(modifier = modifier.clearAndSetSemantics { }) {
        pieces.forEachIndexed { index, piece ->
            // Each piece starts and finishes at its own moment, so they do not move as one body.
            // The clamp is what lets a late piece still complete inside the window.
            val local = ((progress - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed

            val position = when (style) {
                ConfettiStyle.RAIN -> rainOffset(piece, local, size)
                ConfettiStyle.BURST -> burstOffset(piece, local, size)
            }

            drawRect(
                color = colors[index.mod(colors.size)],
                topLeft = position,
                size = Size(piece.width, piece.height),
                // Fades out over the last stretch rather than vanishing on the final frame.
                alpha = ((1f - local) * FADE_GAIN).coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * How the pieces move.
 *
 * A shape of motion rather than a magnitude, which is the whole reason it exists: two crossings
 * that differ only in how much confetti they throw are one animation at two volumes, and a
 * milestone is meant to be a *different thing happening*.
 *
 * @property durationMillis how long the whole burst takes, from the first piece leaving to the
 *   last fading out. The detonation is quicker: it is over almost as soon as it is understood,
 *   where rain is something that goes on for a moment.
 */
enum class ConfettiStyle(val durationMillis: Int) {

    /**
     * Pieces fall from the top edge, staggered, drifting sideways as they go.
     *
     * The ordinary crossing: something is coming down over the banner, which reads as a pleasant
     * background event rather than as an announcement.
     */
    RAIN(durationMillis = 1_800),

    /**
     * Pieces detonate outward from the centre and are dragged down as they slow.
     *
     * The milestone. Not "more rain": the movement runs *away from* the level number rather than
     * past it, which puts the number at the origin of the event instead of behind it — and that
     * is read before a word of the banner has been.
     */
    BURST(durationMillis = 1_250),
}

/** Pieces enter along the top and fall, swaying. */
private fun rainOffset(
    piece: ConfettiPiece,
    local: Float,
    size: Size,
): Offset =
    Offset(
        x = size.width * piece.origin + sin(local * piece.wobble * TAU) * SWAY_PIXELS,
        y = size.height * local * piece.reach,
    )

/**
 * Pieces leave the centre along their own heading, decelerating, with gravity taking over late.
 *
 * The fractional power is the deceleration: distance covered fast at first and barely at all by
 * the end, which is how a thrown thing behaves and is what stops the burst reading as a uniform
 * expanding ring. The gravity term is quadratic and added on top, so the pieces sag out of the
 * circle towards the end rather than curving from the start.
 */
private fun burstOffset(
    piece: ConfettiPiece,
    local: Float,
    size: Size,
): Offset {
    val travelled = piece.reach * local.toDouble().pow(BURST_DECELERATION).toFloat()
    val heading = piece.origin * TAU

    // Elliptical, not circular: the banner is wide and short, and a circle sized to its smaller
    // side leaves a detonation huddled in the middle of a band it never reaches the ends of. Each
    // axis is thrown against its own half-extent, so the burst fills the shape it is drawn in.
    return Offset(
        x = size.width / 2f + cos(heading) * size.width / 2f * BURST_REACH * travelled,
        y = size.height / 2f + sin(heading) * size.height / 2f * BURST_REACH * travelled +
            local * local * size.height * BURST_GRAVITY,
    )
}

/**
 * One piece's fixed properties, read differently by the two styles.
 *
 * @property origin where it comes from: a fraction of the width for [ConfettiStyle.RAIN], a
 *   fraction of a full turn for [ConfettiStyle.BURST]. One field rather than two, because a piece
 *   has exactly one starting condition and a second would only ever be the unused one.
 * @property delay how far into the burst it sets off.
 * @property reach how far it gets — down the canvas, or out from the centre.
 * @property wobble how much it sways. Unused by the burst, which is already travelling in its own
 *   direction and does not need to wander to look alive.
 */
private data class ConfettiPiece(
    val origin: Float,
    val delay: Float,
    val reach: Float,
    val wobble: Float,
    val width: Float,
    val height: Float,
)

private fun confettiPieces(
    seed: Int,
    count: Int,
    style: ConfettiStyle,
): List<ConfettiPiece> {
    val random = Random(seed)
    val minimumReach = if (style == ConfettiStyle.RAIN) MIN_FALL else MIN_THROW
    return List(count) {
        ConfettiPiece(
            origin = random.nextFloat(),
            delay = random.nextFloat() * style.maxDelay,
            reach = minimumReach + random.nextFloat() * (1f - minimumReach),
            wobble = MIN_WOBBLE + random.nextFloat() * (MAX_WOBBLE - MIN_WOBBLE),
            width = MIN_PIECE + random.nextFloat() * (MAX_PIECE - MIN_PIECE),
            height = MIN_PIECE + random.nextFloat() * (MAX_PIECE - MIN_PIECE),
        )
    }
}

/**
 * The latest a piece may set off, as a fraction of the burst.
 *
 * Rain is staggered heavily — that is what makes it fall rather than drop as a sheet. A
 * detonation is not staggered: pieces that left noticeably after the others read as a second,
 * smaller explosion, so they leave almost together.
 */
private val ConfettiStyle.maxDelay: Float
    get() = when (this) {
        ConfettiStyle.RAIN -> 0.35f
        ConfettiStyle.BURST -> 0.08f
    }

private const val DEFAULT_PIECES = 40

/** The shallowest fall, as a fraction of the canvas: no piece stops in the top half. */
private const val MIN_FALL = 0.6f

/** The shortest throw, so no piece merely twitches at the centre. */
private const val MIN_THROW = 0.45f

/**
 * How far the furthest piece is thrown, as a multiple of each half-extent.
 *
 * Above one on purpose: some pieces leave the banner rather than all of them stopping politely
 * inside it, which is what a detonation looks like and what a contained ring does not.
 */
private const val BURST_REACH = 1.1f

/** Below one, so distance is covered early and the pieces are nearly stopped at the end. */
private const val BURST_DECELERATION = 0.55

/** How far gravity drags a piece by the end, as a fraction of the canvas height. */
private const val BURST_GRAVITY = 0.35f

private const val MIN_WOBBLE = 0.5f
private const val MAX_WOBBLE = 2.5f
private const val SWAY_PIXELS = 14f

private const val MIN_PIECE = 6f
private const val MAX_PIECE = 13f

/** Holds a piece at full opacity for the first stretch, so a burst does not open faded. */
private const val FADE_GAIN = 2.2f

private const val TAU = 6.2831855f
