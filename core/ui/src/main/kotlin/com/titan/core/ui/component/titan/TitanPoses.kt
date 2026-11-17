package com.titan.core.ui.component.titan

import com.titan.core.domain.model.TitanActivity
import kotlin.math.floor

/**
 * What every part of the sprite does at one moment of one pose: the canvas's stepped CSS
 * keyframes (*06 Motion*), as data.
 *
 * Every motion is whole sprite pixels on stepped timing — a part holds a position and then jumps
 * — so a frame is a lookup, never an interpolation. A [still] frame is the canvas's
 * reduced-motion frame: readable, mid-pose, and different for every pose.
 *
 * @property offsets where each moved part is shifted, in sprite pixels, added down the tree.
 * @property hidden the parts not drawn in this pose.
 * @property alphas a part's opacity where it is not 1.
 * @property eyeScale the eyes' height, from their bottom edge: shut is a half.
 * @property mouthScale the open mouth's height, from its top edge.
 * @property dashShift how far the ground's dashes have run, in sprite pixels.
 * @property waveShift how far the pool's surface has run, in sprite pixels.
 * @property showDash whether the ground is dashed: the titan is on the move.
 * @property showWater whether it is in the pool.
 * @property clip whether parts outside the canvas are cut off.
 */
internal data class PoseFrame(
    val offsets: Map<SpritePart, Pair<Int, Int>>,
    val hidden: Set<SpritePart>,
    val alphas: Map<SpritePart, Float>,
    val eyeScale: Float,
    val mouthScale: Float,
    val dashShift: Float,
    val waveShift: Int,
    val showDash: Boolean,
    val showWater: Boolean,
    val clip: Boolean,
)

/** The eight poses, from the activity the titan is doing. */
internal object TitanPoses {

    /** How long each pose's slowest loop runs, in milliseconds: the period [frame] repeats on. */
    fun loopMillis(activity: TitanActivity): Long =
        when (activity) {
            TitanActivity.IDLE -> IDLE_LOOP
            TitanActivity.WALK -> WALK_LOOP
            TitanActivity.RUN -> RUN_LOOP
            TitanActivity.EAT -> EAT_LOOP
            TitanActivity.TRAIN -> TRAIN_LOOP
            TitanActivity.SWIM -> SWIM_LOOP
            TitanActivity.SPAR -> SPAR_LOOP
            TitanActivity.REST -> REST_LOOP
        }

    /** Whether the pose shows sparks: only while the titan is exerting. */
    fun sparks(activity: TitanActivity): Boolean =
        activity == TitanActivity.RUN || activity == TitanActivity.TRAIN || activity == TitanActivity.SPAR

    /** The frame of [activity] at [millis] into it, or its still frame. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun frame(
        activity: TitanActivity,
        millis: Long,
        still: Boolean,
    ): PoseFrame {
        val offsets = mutableMapOf<SpritePart, Pair<Int, Int>>()
        val alphas = mutableMapOf<SpritePart, Float>()
        val shown = mutableSetOf<SpritePart>()
        var eye = if (still) 1f else blink(millis)
        var mouth = 1f
        var dash = 0f
        var wave = -WAVE_TRAVEL
        var clip = false

        fun at(
            period: Long,
            phase: Float = 0f,
        ): Float = if (still) 0f else (((millis % period) / period.toFloat()) + phase) % 1f

        when (activity) {
            TitanActivity.IDLE -> offsets[SpritePart.UPPER] = 0 to breathe(at(IDLE_LOOP), still)

            TitanActivity.WALK, TitanActivity.RUN -> {
                val run = activity == TitanActivity.RUN
                val stride = if (run) RUN_STRIDE else WALK_STRIDE
                val t = at(stride)
                if (run) {
                    shown += SpritePart.FX
                    shown += SpritePart.SPEED
                }
                offsets[SpritePart.LEG_L] = 0 to if (still) -1 else stepLeft(t)
                offsets[SpritePart.LEG_R] = 0 to if (still) 0 else stepRight(t)
                offsets[SpritePart.UPPER] = (if (still) 1 else sway(t)) to 0
                if (run) {
                    offsets[SpritePart.LEAN] = 0 to if (still) -1 else bounce(t)
                    if (!still && at(RUN_STRIDE / 2) >= HALF) alphas[SpritePart.SPEED] = 0f
                    if (!still && at(PULSE) >= HALF) alphas[SpritePart.CORE] = PULSE_LOW
                }
                dash = if (still) {
                    0f
                } else {
                    floor(
                        at(if (run) RUN_DASH else WALK_DASH) * DASH_STEPS,
                    ) / DASH_STEPS * DASH_TRAVEL
                }
            }

            TitanActivity.EAT -> {
                shown += SpritePart.FOOD_ACC
                shown += SpritePart.MOUTH
                offsets[SpritePart.UPPER] = 0 to breathe(at(IDLE_LOOP), still)
                val t = at(EAT_LOOP)
                if (!still) {
                    offsets[SpritePart.FOOD] = feedPath(t)
                    if (t >= FEED_GONE) alphas[SpritePart.FOOD] = 0f
                    if (t < BITE_FROM || t >= FEED_GONE) alphas[SpritePart.BITE] = 0f
                    mouth = if (at(CHOMP) >= HALF) HALF else 1f
                } else {
                    alphas[SpritePart.BITE] = 0f
                }
            }

            TitanActivity.TRAIN -> {
                shown += SpritePart.FX
                shown += SpritePart.BAR
                offsets[SpritePart.UPPER] = 0 to if (still) 1 else squat(at(TRAIN_LOOP))
                if (!still && at(PULSE) >= HALF) alphas[SpritePart.CORE] = PULSE_LOW
            }

            TitanActivity.SWIM -> {
                shown += SpritePart.SPLASH
                clip = true
                offsets[SpritePart.ME] = 0 to if (still || at(SWIM_LOOP) < HALF) 1 else 2
                if (still) {
                    alphas[SpritePart.SPLASH_2] = 0f
                } else {
                    alphas[if (at(SPLASH) < HALF) SpritePart.SPLASH_2 else SpritePart.SPLASH_1] = 0f
                    wave = -WAVE_TRAVEL + floor(at(WAVE) * DASH_STEPS).toInt() * WAVE_TRAVEL / DASH_STEPS.toInt()
                }
            }

            TitanActivity.SPAR -> {
                shown += SpritePart.FX
                shown += SpritePart.RIVAL_ACC
                shown += SpritePart.SPARK_ACC
                clip = true
                offsets[SpritePart.ME] = SPAR_STANCE to 0
                val t = at(SPAR_LOOP)
                offsets[SpritePart.LEAN] = (
                    if (still) {
                        JAB
                    } else if (t >= JAB_FROM && t < JAB_TO) {
                        JAB
                    } else {
                        0
                    }
                    ) to 0
                offsets[SpritePart.RIVAL] = (if (!still && t >= RECOIL_FROM && t < RECOIL_TO) JAB else 0) to 0
                // Still, the spark shows: the canvas's still frame is the jab landing.
                if (!still && (t < JAB_FROM || t >= JAB_TO)) alphas[SpritePart.SPARK] = 0f
                if (!still && at(PULSE) >= HALF) alphas[SpritePart.CORE] = PULSE_LOW
            }

            TitanActivity.REST -> {
                shown += SpritePart.Z
                eye = HALF
                offsets[SpritePart.UPPER] = 0 to breathe(at(REST_LOOP), still)
                zzz(at(Z_LOOP), still).let { (y, a) ->
                    offsets[SpritePart.Z1] = 0 to y
                    alphas[SpritePart.Z1] = a
                }
                zzz(at(Z_LOOP, phase = HALF), still).let { (y, a) ->
                    offsets[SpritePart.Z2] = 0 to y
                    alphas[SpritePart.Z2] = a
                }
            }
        }

        val hidden = OPTIONAL - shown
        val removed = when (activity) {
            TitanActivity.EAT -> setOf(SpritePart.SMILE)
            TitanActivity.REST -> setOf(SpritePart.BROW)
            TitanActivity.SWIM -> setOf(SpritePart.LEG_L, SpritePart.LEG_R, SpritePart.SHADOW)
            else -> emptySet()
        }
        return PoseFrame(
            offsets = offsets,
            hidden = hidden + removed,
            alphas = alphas,
            eyeScale = eye,
            mouthScale = mouth,
            dashShift = dash,
            waveShift = wave,
            showDash = activity == TitanActivity.WALK || activity == TitanActivity.RUN,
            showWater = activity == TitanActivity.SWIM,
            clip = clip,
        )
    }

    /** The parts a pose has to switch on: every other part is always drawn. */
    private val OPTIONAL = setOf(
        SpritePart.SPEED,
        SpritePart.RIVAL_ACC,
        SpritePart.FX,
        SpritePart.BAR,
        SpritePart.MOUTH,
        SpritePart.Z,
        SpritePart.FOOD_ACC,
        SpritePart.SPARK_ACC,
        SpritePart.SPLASH,
    )

    // ─── The keyframes, one function each, in sprite pixels ────────────────────────────

    private fun blink(millis: Long): Float {
        val t = (millis % BLINK) / BLINK.toFloat()
        return if (t >= BLINK_SHUT && t < BLINK_OPEN) HALF else 1f
    }

    private fun breathe(
        t: Float,
        still: Boolean,
    ): Int = if (!still && t >= HALF) 1 else 0

    private fun stepLeft(t: Float): Int = if (t < QUARTER) -1 else 0

    private fun stepRight(t: Float): Int = if (t >= HALF && t < THREE_QUARTERS) -1 else 0

    private fun sway(t: Float): Int =
        when {
            t < QUARTER -> 1
            t < HALF -> 0
            t < THREE_QUARTERS -> -1
            else -> 0
        }

    private fun bounce(t: Float): Int = if (t < QUARTER || (t >= HALF && t < THREE_QUARTERS)) -1 else 0

    private fun squat(t: Float): Int =
        when {
            t < SQUAT_DOWN -> 0
            t < SQUAT_BOTTOM -> 1
            t < SQUAT_RISE -> 2
            t < SQUAT_UP -> 1
            else -> 0
        }

    /** The food's way to the mouth, one stop per [FEED_STEP] of the meal, then held there. */
    private fun feedPath(t: Float): Pair<Int, Int> =
        FEED_PATH[(t / FEED_STEP).toInt().coerceAtMost(FEED_PATH.lastIndex)]

    /** A "z" rising and fading over its loop, a quarter at a time; still, it shows where it starts. */
    private fun zzz(
        t: Float,
        still: Boolean,
    ): Pair<Int, Float> = if (still) 0 to 1f else Z_RISE[(t / QUARTER).toInt().coerceAtMost(Z_RISE.lastIndex)]

    private val FEED_PATH = listOf(11 to 7, 7 to 3, 3 to 1, 0 to 0)
    private val Z_RISE = listOf(0 to 0f, -1 to 1f, -2 to 1f, -3 to 0f)

    private const val IDLE_LOOP = 2_400L
    private const val WALK_LOOP = 1_800L
    private const val RUN_LOOP = 1_200L
    private const val EAT_LOOP = 2_000L
    private const val TRAIN_LOOP = 1_800L
    private const val SWIM_LOOP = 2_000L
    private const val SPAR_LOOP = 1_200L
    private const val REST_LOOP = 3_600L

    private const val WALK_STRIDE = 900L
    private const val RUN_STRIDE = 460L
    private const val WALK_DASH = 1_800L
    private const val RUN_DASH = 500L
    private const val PULSE = 1_200L
    private const val CHOMP = 300L
    private const val SPLASH = 1_000L
    private const val WAVE = 1_400L
    private const val Z_LOOP = 2_400L
    private const val BLINK = 4_000L

    private const val QUARTER = 0.25f
    private const val HALF = 0.5f
    private const val THREE_QUARTERS = 0.75f
    private const val PULSE_LOW = 0.35f
    private const val BLINK_SHUT = 0.93f
    private const val BLINK_OPEN = 0.97f
    private const val SQUAT_DOWN = 0.30f
    private const val SQUAT_BOTTOM = 0.45f
    private const val SQUAT_RISE = 0.70f
    private const val SQUAT_UP = 0.85f
    private const val FEED_STEP = 0.12f
    private const val BITE_FROM = 0.5f
    private const val FEED_GONE = 0.7f
    private const val JAB_FROM = 0.40f
    private const val JAB_TO = 0.55f
    private const val RECOIL_FROM = 0.45f
    private const val RECOIL_TO = 0.60f
    private const val JAB = 2
    private const val SPAR_STANCE = -15
    private const val DASH_STEPS = 8f
    private const val DASH_TRAVEL = 8f
    private const val WAVE_TRAVEL = 8
}
