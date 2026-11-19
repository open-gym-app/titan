package com.titan.core.ui.component.titan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import com.titan.core.ui.theme.LocalReducedMotion
import com.titan.core.ui.theme.TitanTheme
import com.titan.core.ui.theme.vital
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The titan's strip above the bottom bar (canvas *Titan dock*): how the titan is present on every
 * tab without the user going to find it.
 *
 * A 72 dp strip. On the left a stage with the titan alone on it — walking, running and swimming
 * cross it and turn round at each end, with reduced motion it stands still in the middle. On the
 * right a 128 dp column: [header], [status] and a thin bar per vital, the [alert] one in the
 * accent. The whole strip is **one button** to a screen reader, reading [contentDescription].
 *
 * @param header the `NAME · LVL n` line, as given (it is drawn uppercase).
 * @param status what the titan is doing, in words.
 * @param alert the one vital to flag, if any.
 */
@Composable
@Suppress("LongParameterList")
fun TitanDock(
    header: String,
    status: String,
    activity: TitanActivity,
    vitals: TitanVitals,
    alert: TitanVital?,
    head: String?,
    face: String?,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DOCK_HEIGHT)
            .background(MaterialTheme.colorScheme.surface)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            }
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            DockStage(activity, head, face, Modifier.weight(1f).fillMaxHeight())
            VerticalDivider(color = rule)
            Column(
                modifier = Modifier
                    .width(COLUMN_WIDTH)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = TitanTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Text(
                    header.uppercase(),
                    style = TitanTheme.typography.eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm)) {
                    TitanVital.entries.forEach { vital -> VitalBar(vital, vitals[vital], alert == vital) }
                }
            }
        }
        HorizontalDivider(color = rule, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** The stage: the ground, the titan, and the water when it swims. Clipped to the strip. */
@Composable
private fun DockStage(
    activity: TitanActivity,
    head: String?,
    face: String?,
    modifier: Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val sprite = TitanTheme.sprite
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val travel = (maxWidth - SPRITE_WIDTH - EDGE * 2).coerceAtLeast(0.dp)
        val lap = LAP_MILLIS[activity]
        val roam = if (lap != null && !reducedMotion) roaming(lap) else remember { mutableFloatStateOf(STILL) }

        HorizontalDivider(
            color = sprite.ground,
            modifier = Modifier.align(Alignment.TopStart).offset(y = GROUND_LINE),
        )
        TitanSprite(
            activity = activity,
            head = head,
            face = face,
            contentDescription = null,
            bare = true,
            modifier = Modifier
                .align(Alignment.BottomStart)
                // Taller than the strip on purpose: the sprite's empty top rows overflow it, so its
                // feet meet the ground line at the design's 2 dp a pixel.
                .requiredSize(SPRITE_WIDTH, SPRITE_HEIGHT)
                // Read in the layout and draw phases only: the walk moves the sprite without
                // recomposing it, so its own pose loop is never restarted by the roaming.
                .offset { IntOffset(roamX(roam.value, EDGE.roundToPx(), travel.roundToPx(), SNAP.roundToPx()), 0) }
                .graphicsLayer { scaleX = if (roam.value > 1f) -1f else 1f },
        )
        if (activity == TitanActivity.SWIM) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset(y = WATER_TOP)
                    .height(DOCK_HEIGHT - WATER_TOP)
                    .background(sprite.water.copy(alpha = WATER_ALPHA)),
            )
            HorizontalDivider(
                color = sprite.waterLine,
                thickness = 2.dp,
                modifier = Modifier.offset(y = WATER_TOP - 2.dp),
            )
        }
    }
}

/** 0 → 2 over a lap: out across the stage on 0..1, back on 1..2. */
@Composable
private fun roaming(lapMillis: Int): State<Float> =
    rememberInfiniteTransition(label = "dock roam").animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(lapMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "dock roam",
    )

/** Where along the stage the titan is at [phase], snapped to whole sprite pixels. */
private fun roamX(
    phase: Float,
    edge: Int,
    travel: Int,
    snap: Int,
): Int {
    val along = 1f - abs(1f - phase)
    val x = (along * travel).roundToInt()
    return edge + x - x % snap.coerceAtLeast(1)
}

@Composable
private fun VitalBar(
    vital: TitanVital,
    value: Int,
    alert: Boolean,
) {
    val tint = TitanTheme.vital(vital).content
    val fill = if (alert) MaterialTheme.colorScheme.primary else tint
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Box(
            Modifier
                .width(BAR_WIDTH)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, BAR_SHAPE),
        ) {
            Box(
                Modifier
                    .width(BAR_WIDTH * (value / TitanVitals.MAX.toFloat()))
                    .height(3.dp)
                    .background(fill, BAR_SHAPE),
            )
        }
    }
}

/** How tall the dock is: what a screen above it gives up. */
val TitanDockHeight: Dp = 72.dp

private val DOCK_HEIGHT = TitanDockHeight
private val COLUMN_WIDTH = 128.dp
private val SPRITE_WIDTH = 128.dp
private val SPRITE_HEIGHT = 80.dp
private val GROUND_LINE = 61.dp
private val WATER_TOP = 45.dp
private val EDGE = 4.dp
private val SNAP = 2.dp
private val BAR_WIDTH = 28.dp
private val BAR_SHAPE = RoundedCornerShape(2.dp)
private const val WATER_ALPHA = 0.85f

/** Where a titan that does not roam stands: the middle of the stage. */
private const val STILL = 0.5f

/** How long a lap of the stage takes, for the activities that cross it. */
private val LAP_MILLIS = mapOf(
    TitanActivity.WALK to 28_000,
    TitanActivity.RUN to 10_000,
    TitanActivity.SWIM to 22_000,
)

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Titan dock states", widthDp = 390, heightDp = 620)
@Composable
fun TitanDockStatesPreview() {
    DockPreviews(ThemePreference.LIGHT)
}

@Preview(name = "Titan dock states, dark", widthDp = 390, heightDp = 620)
@Composable
fun TitanDockStatesDarkPreview() {
    DockPreviews(ThemePreference.DARK)
}

@Composable
private fun DockPreviews(theme: ThemePreference) {
    TitanTheme(theme = theme, reducedMotion = true) {
        Column(
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
        ) {
            listOf(
                Triple(TitanActivity.WALK, "Out for a walk", null),
                Triple(TitanActivity.RUN, "On a run", null),
                Triple(TitanActivity.EAT, "Eating", null),
                Triple(TitanActivity.TRAIN, "Squatting", null),
                Triple(TitanActivity.SWIM, "Swimming", null),
                Triple(TitanActivity.REST, "Resting", null),
                Triple(TitanActivity.WALK, "Hungry", TitanVital.FUEL),
                Triple(TitanActivity.IDLE, "Bored", TitanVital.SPIRIT),
            ).forEach { (activity, status, alert) ->
                TitanDock(
                    header = "Brick · Lvl 14",
                    status = status,
                    activity = activity,
                    vitals = TitanVitals(
                        fuel = if (alert == TitanVital.FUEL) 22 else 64,
                        spirit = if (alert == TitanVital.SPIRIT) 18 else 55,
                    ),
                    alert = alert,
                    head = "headband".takeIf { activity == TitanActivity.TRAIN },
                    face = null,
                    contentDescription = status,
                    onClick = {},
                )
            }
        }
    }
}
