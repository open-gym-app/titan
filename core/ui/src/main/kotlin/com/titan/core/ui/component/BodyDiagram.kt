package com.titan.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.theme.HAIRLINE_WIDTH
import com.titan.core.ui.theme.TitanTheme

/**
 * How hard a muscle was worked, as the diagram draws it.
 *
 * Three levels and no more. A continuous scale — tinting each muscle by its share of the
 * session's volume — was the obvious alternative and is worse: the values it would encode
 * are separated by a few percent, which no eye reads off a 150dp illustration, and the
 * result is a wash of near-identical oranges with no "this one" anywhere in it. Primary and
 * secondary is the distinction the data actually carries
 * ([com.titan.core.domain.model.ExerciseMuscleGroupLink.isPrimary]) and the one a reader
 * can act on.
 */
enum class MuscleEmphasis {

    /** The muscle the movement is for. The accent, at full strength. */
    PRIMARY,

    /** Worked, but not the point of the movement. The accent's container tone. */
    SECONDARY,

    /** Not worked. Drawn in the resting tone, like the rest of the figure. */
    NONE,
}

/**
 * The front and back figures side by side, with the worked muscles highlighted.
 *
 * The illustration `#27`'s muscles-worked section, `#94`'s exercise detail, `#32`'s stats hub
 * and `#109`'s body measurements all draw. It is stateless in the strictest sense — two
 * collections of muscle groups in, a picture out. It has no click handling, because in every
 * one of those four places the diagram is a *readout* sitting beside a written list that is
 * the tappable thing.
 *
 * Both views are drawn by default because a muscle set almost always spans them: a pull day
 * is lats and biceps, and a front-only diagram would report the biceps and silently drop the
 * lats. Pass a single [view] where the caller knows one is enough — an exercise detail for a
 * bench press, say.
 *
 * @param primary the muscles the work was *for*. Drawn in the accent.
 * @param secondary the muscles that assisted. Drawn in the accent's container tone. A muscle
 *   in both collections is drawn as primary; that is not a conflict to resolve but the
 *   ordinary outcome of unioning several exercises, where one movement's assistor is
 *   another's prime mover.
 * @param view which figure to draw, or null (the default) for both.
 * @param height the height of each figure. The width follows from the illustration's aspect
 *   ratio, so this is the only dimension worth naming.
 */
@Composable
fun BodyDiagram(
    primary: Collection<MuscleGroup>,
    modifier: Modifier = Modifier,
    secondary: Collection<MuscleGroup> = emptyList(),
    view: BodyView? = null,
    height: Dp = BodyDiagramDefaults.HEIGHT,
) {
    val primaryRegions = remember(primary) { primary.toRegions() }
    val secondaryRegions = remember(secondary, primaryRegions) { secondary.toRegions() - primaryRegions }
    val description = bodyDiagramDescription(primary = primary, secondary = secondary)
    val views = remember(view) { view?.let(::listOf) ?: BodyView.entries }

    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        views.forEach { each ->
            BodyFigure(
                view = each,
                primary = primaryRegions,
                secondary = secondaryRegions,
                modifier = Modifier.height(height),
            )
        }
    }
}

/**
 * One figure, front or back, driven by drawing regions rather than by domain rows.
 *
 * Public for the callers that have no [MuscleGroup] to hand — a legend, a muscle picker, the
 * previews below. It sets no content description of its own: a region is a shape, and only
 * the caller knows what it was standing in for.
 *
 * Sized by height alone. The width follows from [BODY_WIDTH] / [BODY_HEIGHT] through an
 * `aspectRatio`, so a caller cannot stretch the body by handing it a box of the wrong shape.
 */
@Composable
fun BodyFigure(
    view: BodyView,
    primary: Set<BodyRegion>,
    modifier: Modifier = Modifier,
    secondary: Set<BodyRegion> = emptySet(),
) {
    val geometry = remember(view) { bodyGeometry(view) }
    val scheme = MaterialTheme.colorScheme
    val hairline = with(LocalDensity.current) { HAIRLINE_WIDTH.toPx() }

    Canvas(modifier = modifier.aspectRatio(BODY_WIDTH / BODY_HEIGHT)) {
        val factor = size.height / BODY_HEIGHT
        // The stroke is measured in device pixels but drawn in body units, so it has to be
        // divided back out. Left alone it thickens with the figure, and a 150dp diagram
        // comes out drawn with a three-pixel outline.
        val stroke = Stroke(width = hairline / factor)
        val inset = (size.width - BODY_WIDTH * factor) / 2f

        translate(left = inset) {
            scale(scale = factor, pivot = Offset.Zero) {
                geometry.structure.forEach { part ->
                    drawPath(part, color = scheme.surfaceContainerHigh)
                    drawPath(part, color = scheme.outlineVariant, style = stroke)
                }
                geometry.regions.forEach { (region, parts) ->
                    val emphasis = region.emphasis(primary, secondary)
                    parts.forEach { part -> drawRegion(part, emphasis, scheme, stroke) }
                }
                geometry.details.forEach { detail ->
                    drawPath(detail, color = scheme.outlineVariant, style = stroke)
                }
            }
        }
    }
}

/** Defaults shared by [BodyDiagram] and its callers. */
object BodyDiagramDefaults {

    /**
     * Big enough that a highlighted forearm is still identifiable as a forearm, small enough
     * that two figures and a written legend fit across a 360dp screen.
     */
    val HEIGHT: Dp = 150.dp
}

private fun DrawScope.drawRegion(
    part: Path,
    emphasis: MuscleEmphasis,
    scheme: ColorScheme,
    stroke: Stroke,
) {
    val fill: Color = when (emphasis) {
        MuscleEmphasis.PRIMARY -> scheme.primary
        MuscleEmphasis.SECONDARY -> scheme.primaryContainer
        MuscleEmphasis.NONE -> scheme.surfaceContainerHigh
    }
    // A highlighted muscle is outlined in the accent rather than in the hairline shade. At
    // this size a secondary tint against the resting tone is a small difference in a small
    // shape, and the edge is what makes it read as a *shape* rather than as a smudge.
    val edge: Color = if (emphasis == MuscleEmphasis.NONE) scheme.outlineVariant else scheme.primary

    drawPath(part, color = fill)
    drawPath(part, color = edge, style = stroke)
}

private fun BodyRegion.emphasis(
    primary: Set<BodyRegion>,
    secondary: Set<BodyRegion>,
): MuscleEmphasis =
    when {
        this in primary -> MuscleEmphasis.PRIMARY
        this in secondary -> MuscleEmphasis.SECONDARY
        else -> MuscleEmphasis.NONE
    }

private fun Collection<MuscleGroup>.toRegions(): Set<BodyRegion> = mapNotNullTo(mutableSetOf()) { it.bodyRegion() }

/**
 * What TalkBack reads instead of the picture.
 *
 * Built from the muscle *groups* rather than from the regions they mapped onto, so a group
 * the illustration has no shape for is still announced. That is the case the diagram is
 * least able to convey, and so the one where the description carries the most.
 *
 * Deduplicated, and secondaries minus primaries, the same way [BodyDiagram] resolves the
 * drawing at `secondary.toRegions() - primaryRegions`. Callers union several exercises, where
 * a chest appears three times over and a triceps is one movement's target and another's
 * assistor; read out raw that is "Worked directly: Chest, Chest, Chest. Also worked: Chest." —
 * a sentence the picture then contradicts.
 */
@Composable
private fun bodyDiagramDescription(
    primary: Collection<MuscleGroup>,
    secondary: Collection<MuscleGroup>,
): String {
    val separator = stringResource(R.string.body_muscle_separator)
    // `map` before the rest, not `joinToString { }`: its transform is a nullable parameter,
    // which makes it non-inlinable and so closes it to a composable call.
    val primaryLabels = primary.map { it.displayName() }.distinct()
    val secondaryLabels = secondary.map { it.displayName() }.distinct() - primaryLabels.toSet()

    // By label rather than by group: two rows the user authored as `Chest` and `chest` are one
    // muscle to everyone but the database, and the sentence is what is being read out.
    val primaryNames = primaryLabels.joinToString(separator)
    val secondaryNames = secondaryLabels.joinToString(separator)

    return when {
        primaryNames.isNotEmpty() && secondaryNames.isNotEmpty() ->
            stringResource(R.string.body_diagram_primary_and_secondary, primaryNames, secondaryNames)

        primaryNames.isNotEmpty() -> stringResource(R.string.body_diagram_primary, primaryNames)

        secondaryNames.isNotEmpty() -> stringResource(R.string.body_diagram_secondary, secondaryNames)

        else -> stringResource(R.string.body_diagram_none)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private fun previewMuscle(name: String) = MuscleGroup(id = name.hashCode().toLong(), name = name)

@Preview(name = "Body diagram - push day", widthDp = 360)
@Composable
fun BodyDiagramPushPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        BodyDiagram(
            primary = listOf(previewMuscle("CHEST"), previewMuscle("SHOULDERS")),
            secondary = listOf(previewMuscle("TRICEPS"), previewMuscle("ABDOMINALS")),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Body diagram - pull day, dark", widthDp = 360)
@Composable
fun BodyDiagramPullDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        BodyDiagram(
            primary = listOf(previewMuscle("LATS"), previewMuscle("MIDDLE_BACK")),
            secondary = listOf(
                previewMuscle("BICEPS"),
                previewMuscle("FOREARMS"),
                previewMuscle("TRAPS"),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "Body diagram - nothing worked", widthDp = 360)
@Composable
fun BodyDiagramRestingPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        BodyDiagram(primary = emptyList(), modifier = Modifier.fillMaxWidth())
    }
}

@Preview(name = "Body diagram - leg day, one view", widthDp = 360)
@Composable
fun BodyDiagramSingleViewPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        BodyDiagram(
            primary = listOf(previewMuscle("QUADRICEPS")),
            secondary = listOf(
                previewMuscle("GLUTES"),
                previewMuscle("ADDUCTORS"),
                previewMuscle("CALVES"),
            ),
            view = BodyView.FRONT,
            height = 120.dp,
        )
    }
}
