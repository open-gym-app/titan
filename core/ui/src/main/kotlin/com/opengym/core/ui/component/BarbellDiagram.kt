package com.opengym.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.plate.PlateCount
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A loaded barbell, drawn to scale and mirrored.
 *
 * ### It draws the loading, not the target
 *
 * Everything here comes from [perSide]. If the calculator could not reach the weight the user
 * asked for, this shows the bar as it will actually come off the rack — the shortfall is said
 * in words next to it, by whoever is drawing this. A diagram that quietly drew the *requested*
 * weight would be the one lie the whole feature exists to avoid.
 *
 * ### Size is weight, colour is rank
 *
 * A plate's height is proportional to its weight against the heaviest size on the rack, floored
 * so a change plate is still a plate rather than a line. That is what makes the picture worth
 * having: a bar with two big discs a side and one with six small ones are different shapes
 * before any number is read. Colour, on the other hand, tracks *rank* — see `OpenGymPlateColors`
 * for why that is not the competition colour code.
 *
 * ### Heaviest inwards
 *
 * Plates are drawn in the order they are racked: the biggest against the collar, the change
 * plates on the outside. Somebody loading a bar from this picture is loading it correctly.
 *
 * @param perSide what goes on one end, heaviest first — [com.opengym.core.domain.plate.PlateSolution.Loaded.perSide].
 * @param denominations every size the rack knows, heaviest first, which fixes both the colour
 *   ramp and the scale. Passed rather than derived from [perSide] so the same plate is the same
 *   colour and the same size as the target changes under it — a 10 kg plate that grew because
 *   the 25s came off would make the diagram unreadable as a *comparison*, which is most of what
 *   a user does with it.
 */
@Composable
fun BarbellDiagram(
    perSide: List<PlateCount>,
    denominations: List<Long>,
    modifier: Modifier = Modifier,
) {
    val heaviest = denominations.firstOrNull() ?: perSide.firstOrNull()?.weight ?: 1L
    val plates = perSide.flatMap { plate ->
        // A size the rack does not list gets the lightest tone rather than, through `indexOf`'s
        // -1, the heaviest. It cannot happen from a solved bar — the loading comes out of the
        // same inventory — but a caller drawing a remembered loading against a rack the user has
        // since edited would otherwise paint a change plate in the accent.
        val rank = denominations.indexOf(plate.weight).takeIf { it >= 0 } ?: denominations.size
        List(plate.count) { LoadedPlate(weight = plate.weight, rank = rank) }
    }

    val description = barbellDescription(perSide)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(DIAGRAM_HEIGHT)
            // One node, one sentence. The plates are decoration once the sentence is read, and
            // eleven separate "20 kg" announcements are not a diagram to anybody.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        // Wide loadings are drawn narrower rather than clipped. Ten plates a side is not a rack
        // anybody has, but it is a rack the inventory editor can describe, and half a barbell
        // running off the screen is the worse failure.
        //
        // The factor is applied to *every* horizontal measurement below — the plates, the gaps
        // between them, the collars and the grip — which is what makes it the factor it claims
        // to be: laid-out width comes to `natural * squeeze`, so the row fits by construction.
        // Shrinking only the plates, with the fixed 116dp of collar and grip left at full size,
        // solves a different equation than the one that decides whether it fits, and overflows
        // from about eight plates a side.
        val natural = PLATE_WIDTH * plates.size * 2 + GRIP_WIDTH + COLLAR_WIDTH * 2
        val squeeze = if (natural > maxWidth) maxWidth / natural else 1f

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(BAR_THICKNESS)
                .background(MaterialTheme.colorScheme.onSurface),
        )

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Reversed on the left so both ends read outwards from the middle: the mirror is
            // the point, and a left stack drawn in list order would be a bar loaded backwards.
            plates.asReversed().forEach { Plate(it, heaviest, squeeze) }
            Collar(squeeze)
            Box(Modifier.width(GRIP_WIDTH * squeeze))
            Collar(squeeze)
            plates.forEach { Plate(it, heaviest, squeeze) }
        }
    }
}

/** One plate on the bar, with its rank in the rack — which is what picks its colour. */
private data class LoadedPlate(val weight: Long, val rank: Int)

/**
 * One disc.
 *
 * The weight is stamped on it the way it is stamped on the real thing, rotated to run up the
 * plate, and only where there is room: under [LABELLED_PLATE_HEIGHT] the glyphs would be taller
 * than the disc, and the legend under the diagram carries every weight anyway.
 */
@Composable
private fun Plate(plate: LoadedPlate, heaviest: Long, squeeze: Float) {
    val fraction = (plate.weight.toFloat() / heaviest).coerceIn(0f, 1f)
    val height = PLATE_MIN_HEIGHT + (PLATE_MAX_HEIGHT - PLATE_MIN_HEIGHT) * fraction
    val tone = OpenGymTheme.plateColors.toneFor(plate.rank)

    Surface(
        modifier = Modifier
            // The gap is squeezed with the disc, so the pair of them is one slot of
            // `PLATE_WIDTH * squeeze` — see the squeeze in [BarbellDiagram].
            .padding(horizontal = PLATE_GAP * squeeze)
            .size(width = (PLATE_WIDTH - PLATE_GAP * 2) * squeeze, height = height),
        shape = RoundedCornerShape(PLATE_RADIUS),
        color = tone.fill,
        // Every plate is outlined, because the pale end of both ramps sits close enough to the
        // page for an unbordered disc to lose its edge. See `OpenGymPlateColors`.
        border = BorderStroke(HAIRLINE_WIDTH, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (height >= LABELLED_PLATE_HEIGHT && squeeze == 1f) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = formatWeightValue(plate.weight),
                    style = OpenGymTheme.typography.eyebrow,
                    color = tone.label,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    // Measured along the plate's *height* and then turned, which is the only way
                    // round: rotation does not change what a child was measured against, so a
                    // text laid out in a 14dp column would have wrapped one glyph per line.
                    modifier = Modifier.requiredWidth(height).rotate(-QUARTER_TURN),
                )
            }
        }
    }
}

/** The collar that holds the plates on. Drawn, because a bar without one is a bar being unloaded. */
@Composable
private fun Collar(squeeze: Float) {
    Box(
        modifier = Modifier
            // Width only. The height carries no information about how much is on the bar, so
            // narrowing a crowded diagram should not also flatten it.
            .size(width = COLLAR_WIDTH * squeeze, height = COLLAR_HEIGHT)
            .background(MaterialTheme.colorScheme.onSurface),
    )
}

/** The whole bar as one sentence: what is on each end, biggest first, or that nothing is. */
@Composable
private fun barbellDescription(perSide: List<PlateCount>): String = if (perSide.isEmpty()) {
    stringResource(R.string.plate_diagram_empty)
} else {
    // Resolved into a list first: `joinToString`'s transform is not inline, so a composable
    // call cannot happen inside it.
    val sizes = perSide.map { plate ->
        pluralStringResource(
            R.plurals.plate_count_description,
            plate.count,
            plate.count,
            formatWeight(plate.weight),
        )
    }
    stringResource(
        R.string.plate_diagram_loaded,
        sizes.joinToString(stringResource(R.string.plate_list_separator)),
    )
}

// ─── Metrics ────────────────────────────────────────────────────────────────────────────

/** Tall enough for the biggest plate plus the room a rotated label needs above and below it. */
private val DIAGRAM_HEIGHT = 128.dp

/** The shaft. Thin, because it is the one part of the picture carrying no information. */
private val BAR_THICKNESS = 5.dp

/**
 * The knurled middle.
 *
 * Wide enough that the two stacks read as the *ends* of a bar rather than as a pair of towers
 * with a line through them — which is what a narrow grip looks like once there are three plates
 * a side. It narrows with everything else under [BarbellDiagram]'s squeeze, so a crowded bar is
 * the same picture drawn smaller rather than a differently proportioned one.
 */
private val GRIP_WIDTH = 100.dp

private val COLLAR_WIDTH = 8.dp
private val COLLAR_HEIGHT = 22.dp

/** One plate's slot, gaps included, so the squeeze calculation has a single number to work in. */
private val PLATE_WIDTH = 17.dp
private val PLATE_GAP = 1.dp
private val PLATE_RADIUS = 2.dp

/** A change plate is small, not invisible. */
private val PLATE_MIN_HEIGHT = 20.dp

/** The heaviest size on the rack, whatever it is. */
private val PLATE_MAX_HEIGHT = 96.dp

/**
 * Under this, a plate is not stamped.
 *
 * Set well above the point where the glyphs stop *fitting*, because a number squeezed into a
 * 30dp disc is not read — it is noise across the middle of the picture. On a standard metric
 * rack this stamps the 25, the 20 and the 15 and leaves the change plates clean; the legend
 * under the diagram names every one of them anyway.
 */
private val LABELLED_PLATE_HEIGHT = 56.dp

/** Degrees, anticlockwise, so the weight reads bottom-to-top the way it does on a real plate. */
private const val QUARTER_TURN = 90f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

/** The metric rack, which is also the ramp: five sizes, heaviest first. */
private val PREVIEW_RACK = PlateInventory.METRIC.stock.map { it.weight }

@Composable
private fun BarbellCatalogue(perSide: List<PlateCount>) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = OpenGymTheme.spacing.screen),
    ) {
        BarbellDiagram(perSide = perSide, denominations = PREVIEW_RACK)
    }
}

/** 142.5 kg: the shape the diagram exists for, with a change plate on the outside. */
private val PREVIEW_HEAVY = listOf(
    PlateCount(weight = 20_000_000, count = 3),
    PlateCount(weight = 1_250_000, count = 1),
)

@Preview(name = "Barbell - loaded", widthDp = 360)
@Composable
fun BarbellDiagramPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { BarbellCatalogue(PREVIEW_HEAVY) }
}

@Preview(name = "Barbell - loaded, dark", widthDp = 360)
@Composable
fun BarbellDiagramDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { BarbellCatalogue(PREVIEW_HEAVY) }
}

@Preview(name = "Barbell - a working set", widthDp = 360)
@Composable
fun BarbellDiagramWorkingPreview() {
    // 100 kg, and the two sizes are two tones apart — the ordinary case, not the busy one.
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        BarbellCatalogue(
            listOf(
                PlateCount(weight = 20_000_000, count = 1),
                PlateCount(weight = 10_000_000, count = 2),
            ),
        )
    }
}

@Preview(name = "Barbell - the bar alone", widthDp = 360)
@Composable
fun BarbellDiagramEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { BarbellCatalogue(emptyList()) }
}

@Preview(name = "Barbell - more plates than fit", widthDp = 360)
@Composable
fun BarbellDiagramCrowdedPreview() {
    // Ten plates a side, which the default rack can genuinely reach — the squeeze, which is the
    // only part of the layout a preview can go wrong in without anybody noticing. Nothing may
    // touch either edge of the frame; that is what this preview is for.
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        BarbellCatalogue(
            listOf(
                PlateCount(weight = 20_000_000, count = 8),
                PlateCount(weight = 2_500_000, count = 2),
            ),
        )
    }
}
