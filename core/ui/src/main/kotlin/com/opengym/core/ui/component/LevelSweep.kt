package com.opengym.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.opengym.core.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/**
 * A level bar swept through every threshold a session crossed, one at a time.
 *
 * ### Why the crossings are played rather than skipped
 *
 * A first workout can pay four levels at once. Drawing the result — the final level, the bar a
 * third full — reports where the user ended and shows them none of what happened: the four
 * thresholds they passed are the whole of what makes a first session feel like something. So the
 * bar runs to the end, the level ticks over, the bar drops to empty and runs again, once per
 * level gained.
 *
 * ### The pause at the threshold is the effect
 *
 * Each sweep decelerates hard over its last stretch and then **holds** at full for a beat before
 * the number changes. That hold is doing the work: an animation that ran the bar to the end and
 * flipped the level in the same frame reads as a glitch, because the eye never catches the moment
 * the threshold is reached. Slowing into it and stopping there gives the crossing somewhere to
 * happen, and is what the level number's own flourish is timed against — see [LevelSweep.crossings].
 *
 * The final segment does not decelerate that way. It is not arriving anywhere; it stops wherever
 * the user now stands, so it eases out normally.
 *
 * ### It runs once
 *
 * Same contract as [CountUpNumber] and [ConfettiBurst]: held across configuration changes, so a
 * rotation neither restarts nor replays the sweep, and immediate under reduced motion — where the
 * bar is simply drawn at its final level and fraction, with [LevelSweep.crossings] never firing.
 * A user who asked for stillness gets the outcome, not a faster version of the ceremony.
 *
 * @param fromLevel the level held before the session.
 * @param fromFraction how far into [fromLevel] the user stood, `0f..1f`.
 * @param toLevel the level held after. Equal to [fromLevel] when nothing was crossed, in which
 *   case this is a single sweep and no threshold is announced.
 * @param toFraction how far into [toLevel] the user now stands.
 */
@Composable
fun rememberLevelSweep(
    fromLevel: Int,
    fromFraction: Float,
    toLevel: Int,
    toFraction: Float,
): LevelSweep {
    val reducedMotion = LocalReducedMotion.current
    val crossingCount = (toLevel - fromLevel).coerceAtLeast(0)

    var level by rememberSaveable { mutableIntStateOf(if (reducedMotion) toLevel else fromLevel) }
    var crossings by rememberSaveable { mutableIntStateOf(0) }

    // Whether this instance has already played. `rememberSaveable` so a rotation mid-sweep lands
    // on the finished state instead of starting the ceremony again.
    var played by rememberSaveable { mutableStateOf(reducedMotion) }

    val fraction = remember {
        Animatable(if (reducedMotion) toFraction else fromFraction.coerceIn(0f, 1f))
    }

    LaunchedEffect(fromLevel, toLevel, reducedMotion) {
        if (played) {
            // Restored, or reduced motion: settle on the answer with nothing to watch.
            level = toLevel
            fraction.snapTo(toFraction.coerceIn(0f, 1f))
            return@LaunchedEffect
        }

        repeat(crossingCount) { step ->
            fraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = SWEEP_MILLIS, easing = ArriveAtThreshold),
            )
            // The beat the crossing happens in. Nothing moves; that is the point.
            delay(HOLD_MILLIS.toLong())
            level = fromLevel + step + 1
            crossings = step + 1
            fraction.snapTo(0f)
        }

        fraction.animateTo(
            targetValue = toFraction.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = SETTLE_MILLIS, easing = LinearOutSlowInEasing),
        )
        played = true
    }

    return remember(level, crossings) {
        LevelSweep(
            level = level,
            crossings = crossings,
            ceremony = LevelCeremony.of(level),
            fraction = fraction.asState(),
        )
    }
}

/**
 * Where the bar is, which level it is counting into, and how many thresholds have gone by.
 *
 * @property level the level currently on screen, which climbs as the sweep passes thresholds.
 * @property crossings how many have been passed so far. A caller keys its own flourish — a
 *   flash, a burst — on this, so each threshold gets its own rather than one at the end. Zero
 *   until the first, and zero for the whole of a session that crossed nothing.
 * @property ceremony how much noise [level] is worth. Recomputed as the sweep climbs, so a run
 *   that passes an ordinary level and lands on a milestone is quiet at the first and loud at the
 *   second — see [LevelCeremony].
 * @property fraction how full the bar is, `0f..1f`. A [State] rather than a plain value so a
 *   caller can read it in a draw scope without recomposing on every frame.
 */
@Stable
class LevelSweep(
    val level: Int,
    val crossings: Int,
    val ceremony: LevelCeremony,
    val fraction: State<Float>,
)

/**
 * How a sweep arrives at a threshold: quickly at first, then very slowly over the last stretch.
 *
 * The deceleration is far heavier than a standard ease-out, and deliberately so — this is the
 * "nearly there" the effect is made of. A standard curve arrives too confidently to read as
 * anticipation.
 */
private val ArriveAtThreshold = CubicBezierEasing(0.1f, 0.85f, 0.15f, 1f)

/** How long one full sweep takes. */
private const val SWEEP_MILLIS = 620

/** The beat held at full before the level ticks over. */
private const val HOLD_MILLIS = 260

/** The last, shorter run into wherever the user actually stands. */
private const val SETTLE_MILLIS = 520
