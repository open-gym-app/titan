package com.opengym.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.plate.PlateCount
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.BarbellDiagram
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.MetaEntry
import com.opengym.core.ui.component.MetaGrid
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.format.formatAsClock
import com.opengym.core.ui.format.formatWeight
import kotlin.time.Duration.Companion.seconds

/*
 * Every token in the design system, rendered, in both schemes.
 *
 * It lives in `src/test` for the same reason `example/ExampleScreen` does: it is
 * documentation of the design system, not a component anything is meant to use, so it must
 * not ship in the library. It is still an ordinary set of `@Preview`s — open this file in
 * Android Studio to read the tokens, and the Roborazzi plugin captures every one of them as
 * a screenshot reference, which turns an accidental token change into a reviewable image
 * diff.
 *
 * One sheet per family rather than one scroll, because a `@Preview` renders at the device's height
 * and silently clips whatever runs past it — a single catalog captured its colours and lost
 * the type scale and the spacing scale entirely. Splitting also keeps the diffs honest: a
 * change to the type scale re-records the type sheet and leaves the others untouched.
 */

/** The accent family and the error family — everything that is not a neutral. */
@Composable
fun ColorCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        val scheme = MaterialTheme.colorScheme
        CatalogSection("COLOUR · CORE") {
            ColorSpecimen("primary", scheme.primary, scheme.onPrimary)
            ColorSpecimen("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer)
            ColorSpecimen("secondary", scheme.secondary, scheme.onSecondary)
            ColorSpecimen("secondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer)
            ColorSpecimen("tertiary", scheme.tertiary, scheme.onTertiary)
            ColorSpecimen("error", scheme.error, scheme.onError)
            ColorSpecimen("errorContainer", scheme.errorContainer, scheme.onErrorContainer)
        }
    }
}

/** The neutral ramp: the surface levels a screen stacks, and the two line weights. */
@Composable
fun SurfaceCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        val scheme = MaterialTheme.colorScheme
        CatalogSection("COLOUR · SURFACES") {
            ColorSpecimen("background · paper", scheme.background, scheme.onBackground)
            ColorSpecimen("surfaceContainerLow · card", scheme.surfaceContainerLow, scheme.onSurface)
            ColorSpecimen("surfaceContainer · fill", scheme.surfaceContainer, scheme.onSurface)
            ColorSpecimen("surfaceContainerHigh", scheme.surfaceContainerHigh, scheme.onSurface)
            ColorSpecimen("surfaceContainerHighest", scheme.surfaceContainerHighest, scheme.onSurface)
        }
        CatalogSection("COLOUR · LINES") {
            ColorSpecimen("outline · control edge", scheme.outline, scheme.surface)
            ColorSpecimen("outlineVariant · hairline", scheme.outlineVariant, scheme.onSurface)
        }
    }
}

/**
 * The plate ramp, and the barbell it exists for.
 *
 * A sheet of its own because it is the one token set that is neither a Material slot nor a
 * measurement: seven ordinal tones, each with the label colour that reads on it, plus the
 * component that spends them. Drawn together so a change to a tone is reviewable as *what a
 * loaded bar looks like* rather than as seven swatches.
 */
@Composable
fun PlateCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        CatalogSection("COLOUR · PLATES") {
            OpenGymTheme.plateColors.tones.forEachIndexed { rank, tone ->
                ColorSpecimen("plate · rank $rank", tone.fill, tone.label)
            }
        }
        CatalogSection("BARBELL") {
            BarbellDiagram(
                perSide = listOf(
                    PlateCount(weight = 25_000_000, count = 2),
                    PlateCount(weight = 10_000_000, count = 1),
                    PlateCount(weight = 2_500_000, count = 1),
                ),
                denominations = PlateInventory.METRIC.stock.map { it.weight },
            )
        }
    }
}

/** The Material roles on the wireframes' scale, and the two roles Material does not have. */
@Composable
fun TypeCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        CatalogSection("TYPE · MATERIAL ROLES") {
            TypeSpecimen("titleLarge", MaterialTheme.typography.titleLarge)
            TypeSpecimen("titleMedium", MaterialTheme.typography.titleMedium)
            TypeSpecimen("titleSmall", MaterialTheme.typography.titleSmall)
            TypeSpecimen("bodyLarge", MaterialTheme.typography.bodyLarge)
            TypeSpecimen("bodyMedium", MaterialTheme.typography.bodyMedium)
            TypeSpecimen("bodySmall", MaterialTheme.typography.bodySmall)
            TypeSpecimen("labelLarge", MaterialTheme.typography.labelLarge)
            TypeSpecimen("labelMedium", MaterialTheme.typography.labelMedium)
        }
        CatalogSection("TYPE · OPENGYM ROLES") {
            TypeSpecimen("eyebrow", OpenGymTheme.typography.eyebrow, sample = "THIS WEEK")
            TypeSpecimen("numericDisplay", OpenGymTheme.typography.numericDisplay, sample = "1:23")
            TypeSpecimen("numericLarge", OpenGymTheme.typography.numericLarge, sample = "01:30")
            TypeSpecimen("numeric", OpenGymTheme.typography.numeric, sample = "102.5 kg x 8")
            TypeSpecimen("numericSmall", OpenGymTheme.typography.numericSmall, sample = "11040 kg")
            // Tabular figures earn their keep here: 1 and 0 occupy the same width, so a
            // countdown does not shuffle the layout as it ticks.
            TypeSpecimen("· tabular", OpenGymTheme.typography.numeric, sample = "111111 / 000000")
        }
    }
}

/** The spacing rhythm and the corner radii. */
@Composable
fun LayoutCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        CatalogSection("SPACING") {
            SpacingSpecimen("xs", OpenGymTheme.spacing.xs)
            SpacingSpecimen("sm", OpenGymTheme.spacing.sm)
            SpacingSpecimen("md", OpenGymTheme.spacing.md)
            SpacingSpecimen("lg", OpenGymTheme.spacing.lg)
            SpacingSpecimen("xl · card", OpenGymTheme.spacing.xl)
            SpacingSpecimen("xxl · screen", OpenGymTheme.spacing.xxl)
        }
        CatalogSection("SHAPE") {
            Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
                ShapeSpecimen("4", MaterialTheme.shapes.extraSmall)
                ShapeSpecimen("6", MaterialTheme.shapes.small)
                ShapeSpecimen("8", MaterialTheme.shapes.medium)
                ShapeSpecimen("12", MaterialTheme.shapes.large)
                ShapeSpecimen("pill", PillShape)
            }
        }
    }
}

/**
 * The primitives built out of the tokens: the box, its dividers, the eyebrow, the badges.
 *
 * A fifth sheet rather than a line in the layout one, because these are the things a screen
 * actually composes with — a token change that looks harmless in isolation shows up here as
 * a card that no longer reads as a card.
 */
@Composable
fun ComponentCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        CatalogSection("EYEBROW") {
            Eyebrow("Appearance")
            Eyebrow("This week", color = MaterialTheme.colorScheme.primary)
        }
        CatalogSection("BADGE") {
            Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
                OpenGymBadge("PR", tone = BadgeTone.ACCENT)
                OpenGymBadge("soon")
                OpenGymBadge("missed", tone = BadgeTone.CRITICAL)
            }
        }
        CatalogSection("SECTION HEADING") {
            SectionHeading(text = "Muscles worked")
            SectionHeading(text = "Resume", color = MaterialTheme.colorScheme.primary)
        }
        CatalogSection("CARD") {
            OpenGymCard(modifier = Modifier.fillMaxWidth()) {
                CatalogCardRow("Theme", "Auto")
                HairlineDivider()
                CatalogCardRow("Default rest", 120.seconds.formatAsClock())
                HairlineDivider()
                CatalogCardRow("Bar weight", formatWeight(20_000_000, UnitSystem.METRIC))
            }
        }
        CatalogSection("META GRID") {
            MetaGrid(
                entries = listOf(
                    MetaEntry(label = "Equipment", value = "Barbell"),
                    MetaEntry(label = "Difficulty", value = "Intermediate"),
                    MetaEntry(label = "Tracked", value = "Weight, reps, RPE"),
                ),
            )
        }
    }
}

/** A card row at the density the settings screen uses it. */
@Composable
private fun CatalogCardRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OpenGymTheme.spacing.xl, vertical = OpenGymTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CatalogSheet(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
            content = content,
        )
    }
}

@Composable
private fun CatalogSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
        Text(
            text = title,
            style = OpenGymTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun ColorSpecimen(name: String, color: Color, onColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 32.dp)
                .background(color, MaterialTheme.shapes.extraSmall)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Aa 88", style = OpenGymTheme.typography.numericSmall, color = onColor)
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TypeSpecimen(name: String, style: TextStyle, sample: String = "Bench press") {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = name,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp),
        )
        Text(text = sample, style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SpacingSpecimen(name: String, value: Dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = name,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Box(
            modifier = Modifier
                .width(value)
                .height(12.dp)
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall),
        )
        Text(
            text = "${value.value.toInt()}",
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShapeSpecimen(name: String, shape: Shape) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        )
        Text(
            text = name,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Catalog - colour - light", widthDp = 360)
@Composable
fun ColorCatalogLightPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { ColorCatalog() }
}

@Preview(name = "Catalog - colour - dark", widthDp = 360)
@Composable
fun ColorCatalogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { ColorCatalog() }
}

@Preview(name = "Catalog - surfaces - light", widthDp = 360)
@Composable
fun SurfaceCatalogLightPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { SurfaceCatalog() }
}

@Preview(name = "Catalog - surfaces - dark", widthDp = 360)
@Composable
fun SurfaceCatalogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { SurfaceCatalog() }
}

@Preview(name = "Catalog - type - light", widthDp = 360)
@Composable
fun TypeCatalogLightPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { TypeCatalog() }
}

@Preview(name = "Catalog - type - dark", widthDp = 360)
@Composable
fun TypeCatalogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { TypeCatalog() }
}

@Preview(name = "Catalog - layout - light", widthDp = 360)
@Composable
fun LayoutCatalogLightPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { LayoutCatalog() }
}

@Preview(name = "Catalog - layout - dark", widthDp = 360)
@Composable
fun LayoutCatalogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { LayoutCatalog() }
}

@Preview(name = "Catalog - components - light", widthDp = 360)
@Composable
fun ComponentCatalogLightPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { ComponentCatalog() }
}

@Preview(name = "Catalog - components - dark", widthDp = 360)
@Composable
fun ComponentCatalogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { ComponentCatalog() }
}

@Preview(name = "Catalog - plates - light", widthDp = 360)
@Composable
fun PlateCatalogLightPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { PlateCatalog() }
}

@Preview(name = "Catalog - plates - dark", widthDp = 360)
@Composable
fun PlateCatalogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { PlateCatalog() }
}
