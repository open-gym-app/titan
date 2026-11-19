package com.titan.core.ui.component.titan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import com.titan.core.ui.R
import com.titan.core.ui.theme.HAIRLINE_WIDTH
import com.titan.core.ui.theme.LocalReducedMotion
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme
import com.titan.core.ui.theme.vital

/*
 * The small pieces the titan's screens are built from (canvas *04 Components*): the vital row,
 * the item tile and the effect chip. Stateless and hoisted, like every
 * shared component; each is one node to a screen reader, with its state in what it says.
 */

// ─── Vital row ──────────────────────────────────────────────────────────────────────────

/**
 * One vital: its icon on the vital's container, its name, `62 / 100`, and a bar.
 *
 * When this is the vital the titan is alerting on ([alert]: the lowest, and under 25 — see
 * `TitanRules.alertFor`) the value reads `LOW · 22 / 100` and the bar turns to the accent. The
 * word is there as well as the colour, so the alert never depends on colour alone. The bar
 * counts up over 600 ms; with reduced motion it is drawn at its value.
 */
@Composable
fun TitanVitalRow(
    vital: TitanVital,
    value: Int,
    alert: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = TitanTheme.vital(vital)
    val name = vital.label()
    val figure = stringResource(R.string.titan_vital_value, value, TitanVitals.MAX)
    val shown = if (alert) {
        stringResource(
            R.string.badge_with_qualifier,
            stringResource(R.string.titan_vital_low),
            figure,
        )
    } else {
        figure
    }
    val spoken = if (alert) {
        stringResource(R.string.titan_vital_low_description, name, value, TitanVitals.MAX)
    } else {
        stringResource(R.string.titan_vital_description, name, value, TitanVitals.MAX)
    }
    val accent = MaterialTheme.colorScheme.primary
    val reducedMotion = LocalReducedMotion.current
    val fill = remember { Animatable(if (reducedMotion) value.toFloat() else 0f) }
    LaunchedEffect(value, reducedMotion) {
        if (reducedMotion) fill.snapTo(value.toFloat()) else fill.animateTo(value.toFloat(), tween(BAR_COUNT_MILLIS))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = spoken
                progressBarRangeInfo = ProgressBarRangeInfo(fill.value, 0f..TitanVitals.MAX.toFloat())
            }
            .padding(vertical = TitanTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        ) {
            Box(
                modifier = Modifier.size(VITAL_ICON_CIRCLE).background(colors.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    TitanIcons.of(vital),
                    contentDescription = null,
                    tint = colors.content,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                shown.uppercase(),
                style = TitanTheme.typography.numericSmall,
                color = if (alert) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fill.value / TitanVitals.MAX },
            modifier = Modifier.fillMaxWidth().padding(start = VITAL_ICON_CIRCLE + TitanTheme.spacing.lg).height(6.dp),
            color = if (alert) accent else colors.content,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

// ─── Item tile ──────────────────────────────────────────────────────────────────────────

/** What an item tile shows about the thing it stands for. */
enum class TitanItemState {

    /** Held, nothing to say about it. */
    STOCKED,

    /** A drop the user has not looked at yet: accent outline and tag. */
    NEW,

    /** On the titan: a 2 dp ink rule along the top, like the tab marker. */
    WORN,

    /** None held, or not unlocked: dashed, faint, still listed. */
    EMPTY,
}

/**
 * One item of the supplies grid: an icon on its container, a name, and a mono line under it
 * (`×6`, `WORN`, `LVL 20`).
 *
 * @param spokenDetail what the mono line means, said in words ("1 left", "level 20").
 */
@Composable
@Suppress("LongParameterList")
fun TitanItemTile(
    icon: ImageVector,
    name: String,
    detail: String,
    spokenDetail: String,
    state: TitanItemState,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val faint = state == TitanItemState.EMPTY
    val ink = if (faint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val rule = MaterialTheme.colorScheme.onSurface
    val stateWord = when (state) {
        TitanItemState.NEW -> stringResource(R.string.titan_item_new)
        TitanItemState.WORN -> stringResource(R.string.titan_item_worn)
        else -> null
    }
    val spoken = listOfNotNull(name, spokenDetail, stateWord).joinToString(stringResource(R.string.spoken_separator))
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .heightIn(min = MIN_TOUCH_TARGET)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                if (state == TitanItemState.WORN) selected = true
                onClick {
                    onClick()
                    true
                }
            }
            .drawWithContent {
                drawContent()
                val radius = CornerRadius(6.dp.toPx())
                when {
                    faint -> drawRoundRect(
                        outline,
                        cornerRadius = radius,
                        style = Stroke(
                            1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                        ),
                    )

                    state == TitanItemState.NEW -> drawRoundRect(
                        accent,
                        cornerRadius = radius,
                        style = Stroke(1.dp.toPx()),
                    )

                    else -> drawRoundRect(outline, cornerRadius = radius, style = Stroke(HAIRLINE_WIDTH.toPx()))
                }
                if (state == TitanItemState.WORN) {
                    drawRect(
                        rule,
                        topLeft = Offset(14.dp.toPx(), 0f),
                        size = Size(size.width - 28.dp.toPx(), 2.dp.toPx()),
                    )
                }
            },
    ) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = if (faint) Color.Transparent else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.size(
                        40.dp,
                    ).background(
                        if (faint) MaterialTheme.colorScheme.surfaceContainerHighest else container,
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (faint) ink else content,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(detail.uppercase(), style = TitanTheme.typography.eyebrow, color = ink, maxLines = 1)
            }
        }
        if (state == TitanItemState.NEW) {
            Text(
                stringResource(R.string.titan_item_new).uppercase(),
                style = TitanTheme.typography.eyebrow,
                color = accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp),
            )
        }
    }
}

// ─── Effect chip ────────────────────────────────────────────────────────────────────────

/** `+40 FUEL`, in mono caps on the vital's container: what a feed or a bout just did. */
@Composable
fun TitanEffectChip(
    vital: TitanVital,
    amount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = TitanTheme.vital(vital)
    val name = vital.label()
    val spoken = stringResource(R.string.titan_effect_description, name, amount)
    Text(
        text = stringResource(R.string.titan_effect, amount, name).uppercase(),
        style = TitanTheme.typography.eyebrow,
        color = colors.content,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .background(colors.container, RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private val VITAL_ICON_CIRCLE = 28.dp
private const val BAR_COUNT_MILLIS = 600

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Titan components", widthDp = 360, heightDp = 720)
@Composable
fun TitanComponentsPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, reducedMotion = true) { ComponentCatalogue() }
}

@Preview(name = "Titan components, dark", widthDp = 360, heightDp = 720)
@Composable
fun TitanComponentsDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, reducedMotion = true) { ComponentCatalogue() }
}

@Composable
private fun ComponentCatalogue() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        ) {
            TitanVitalRow(TitanVital.FUEL, value = 22, alert = true)
            TitanVitalRow(TitanVital.SPIRIT, value = 55, alert = false)
            val fuel = TitanTheme.vital(TitanVital.FUEL)
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                listOf(
                    Triple("steak", "×1", TitanItemState.NEW),
                    Triple("eggs", "×6", TitanItemState.STOCKED),
                    Triple("rice", "×0", TitanItemState.EMPTY),
                ).forEach { (id, detail, state) ->
                    TitanItemTile(
                        TitanIcons.food(id), foodLabel(id), detail, detail, state, fuel.container, fuel.content, {},
                        modifier = Modifier.weight(1f),
                    )
                }
                TitanItemTile(
                    TitanIcons.of(com.titan.core.domain.model.Gear.HEADBAND),
                    com.titan.core.domain.model.Gear.HEADBAND.label(),
                    stringResource(R.string.titan_item_worn),
                    stringResource(R.string.titan_item_worn),
                    TitanItemState.WORN,
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurface,
                    {},
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                TitanEffectChip(TitanVital.FUEL, amount = 40)
                TitanEffectChip(TitanVital.SPIRIT, amount = 8)
                Box(Modifier.width(1.dp))
            }
        }
    }
}
