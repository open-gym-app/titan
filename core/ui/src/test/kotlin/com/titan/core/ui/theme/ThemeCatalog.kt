package com.titan.core.ui.theme

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
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.plate.PlateCount
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.ui.component.BadgeTone
import com.titan.core.ui.component.BarbellDiagram
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.LevelProgressBar
import com.titan.core.ui.component.MetaEntry
import com.titan.core.ui.component.MetaGrid
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.SelectableOption
import com.titan.core.ui.component.StepProgress
import com.titan.core.ui.component.StreakShields
import com.titan.core.ui.component.TitanBadge
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatAsClock
import com.titan.core.ui.format.formatWeight
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
            TitanTheme.plateColors.tones.forEachIndexed { rank, tone ->
                ColorSpecimen("plate · rank $rank", tone.fill, tone.label)
            }
        }
        CatalogSection("BARBELL") {
            BarbellDiagram(
                perSide = listOf(
                    PlateCount(weight = 20_000_000, count = 2),
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
        CatalogSection("TYPE · TITAN ROLES") {
            TypeSpecimen("eyebrow", TitanTheme.typography.eyebrow, sample = "THIS WEEK")
            TypeSpecimen("numericDisplay", TitanTheme.typography.numericDisplay, sample = "1:23")
            TypeSpecimen("numericLarge", TitanTheme.typography.numericLarge, sample = "01:30")
            TypeSpecimen("numeric", TitanTheme.typography.numeric, sample = "102.5 kg x 8")
            TypeSpecimen("numericSmall", TitanTheme.typography.numericSmall, sample = "11040 kg")
            // Tabular figures earn their keep here: 1 and 0 occupy the same width, so a
            // countdown does not shuffle the layout as it ticks.
            TypeSpecimen("· tabular", TitanTheme.typography.numeric, sample = "111111 / 000000")
        }
    }
}

/** The spacing rhythm and the corner radii. */
@Composable
fun LayoutCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        CatalogSection("SPACING") {
            SpacingSpecimen("xs", TitanTheme.spacing.xs)
            SpacingSpecimen("sm", TitanTheme.spacing.sm)
            SpacingSpecimen("md", TitanTheme.spacing.md)
            SpacingSpecimen("lg", TitanTheme.spacing.lg)
            SpacingSpecimen("xl · card", TitanTheme.spacing.xl)
            SpacingSpecimen("xxl · screen", TitanTheme.spacing.xxl)
        }
        CatalogSection("SHAPE") {
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                TitanBadge("PR", tone = BadgeTone.ACCENT)
                TitanBadge("soon")
                TitanBadge("missed", tone = BadgeTone.CRITICAL)
            }
        }
        CatalogSection("SECTION HEADING") {
            SectionHeading(text = "Muscles worked")
            SectionHeading(text = "Resume", color = MaterialTheme.colorScheme.primary)
        }
        CatalogSection("CARD") {
            TitanCard(modifier = Modifier.fillMaxWidth()) {
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

/**
 * The payoff's own palette, and the two marks that spend it.
 *
 * A sheet of its own for the same reason the plate ramp has one: [TitanCelebrationColors] is
 * a token set Material has no slot for, and it is the *documented exception* to the app's
 * one-accent rule — so it is the set most worth having a picture of. Four of its tones carry
 * text and are held to AA pair by pair in `ColorSchemeContrastTest`; the confetti tones carry
 * nothing at all, which is what makes them safe, and they appear here as swatches rather than
 * as a burst because a still frame of a particle effect is not a review of anything.
 *
 * `ConfettiBurst` is deliberately absent. It draws nothing under reduced motion — see its own
 * doc — and this sheet is captured with motion off, so the screenshot would be an empty box.
 *
 * The streak allowance closes the sheet. It is not a celebration token — it spends `primary`
 * and `outlineVariant` like the rest of the app — but it belongs to the same family of marks:
 * things the gamification layer draws to report a resource rather than a quantity.
 */
@Composable
fun CelebrationCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        val celebration = TitanTheme.celebration
        CatalogSection("COLOUR · CELEBRATION") {
            ColorSpecimen("celebration · xp", celebration.xp, celebration.onXp)
            ColorSpecimen("celebration · level up", celebration.levelUp, celebration.onLevelUp)
            ColorSpecimen("celebration · level up / edge", celebration.levelUpEdge, celebration.onLevelUp)
            ColorSpecimen("celebration · achievement", celebration.achievement, celebration.onAchievement)
        }
        CatalogSection("COLOUR · CONFETTI") {
            celebration.confetti.forEachIndexed { index, tone ->
                ColorSpecimen("confetti · $index", tone, MaterialTheme.colorScheme.onSurface)
            }
        }
        CatalogSection("LEVEL PROGRESS") {
            LevelProgressBar(
                progress = { CATALOG_LEVEL_PROGRESS },
                contentDescription = "Level 14, 43 of 100 XP",
                color = celebration.xp,
                trackColor = celebration.xpTrack,
            )
        }
        CatalogSection("STREAK SHIELDS") {
            StreakShields(
                remaining = CATALOG_ALLOWANCE,
                allowance = CATALOG_ALLOWANCE,
                contentDescription = "the month's streak protections, none spent",
            )
            StreakShields(
                remaining = CATALOG_ALLOWANCE - 1,
                allowance = CATALOG_ALLOWANCE,
                contentDescription = "one streak protection spent",
            )
            StreakShields(
                remaining = 0,
                allowance = CATALOG_ALLOWANCE,
                contentDescription = "no streak protections left",
            )
        }
    }
}

/**
 * What a question asked *as a screen* is made of: the option card and the step rail.
 *
 * A sheet of its own rather than two more sections on [ComponentCatalog], which already fills
 * a device height — see the note at the top of the file. It is also the pair's natural
 * grouping: neither is much use without the other.
 */
@Composable
fun ChoiceCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        CatalogSection("SELECTABLE OPTION") {
            // Both states together, because the pair *is* the token: selection is the accent
            // border over the accent-soft fill, at the same hairline weight as every other
            // line in the app. See `SelectableOption` for why the border does not thicken.
            SelectableOption(
                label = "Hypertrophy",
                detail = "Volume and time under tension",
                selected = true,
                onClick = {},
            )
            SelectableOption(
                label = "Endurance",
                detail = "High reps, short rests",
                selected = false,
                onClick = {},
            )
        }
        CatalogSection("STEP PROGRESS") {
            StepProgress(step = 1, steps = 4)
            StepProgress(step = 2, steps = 4)
            StepProgress(step = 4, steps = 4)
        }
    }
}

/** A card row at the density the settings screen uses it. */
@Composable
private fun CatalogCardRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanTheme.spacing.xl, vertical = TitanTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CatalogSheet(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
            content = content,
        )
    }
}

@Composable
private fun CatalogSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        Text(
            text = title,
            style = TitanTheme.typography.eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable
private fun ColorSpecimen(
    name: String,
    color: Color,
    onColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 32.dp)
                .background(color, MaterialTheme.shapes.extraSmall)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "Aa 88", style = TitanTheme.typography.numericSmall, color = onColor)
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TypeSpecimen(
    name: String,
    style: TextStyle,
    sample: String = "Bench press",
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Text(
            text = name,
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp),
        )
        Text(text = sample, style = style, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SpacingSpecimen(
    name: String,
    value: Dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Text(
            text = name,
            style = TitanTheme.typography.numericSmall,
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
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShapeSpecimen(
    name: String,
    shape: Shape,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        )
        Text(
            text = name,
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "Catalog - colour - light", widthDp = 360)
@Composable
fun ColorCatalogLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) { ColorCatalog() }
}

@Preview(name = "Catalog - colour - dark", widthDp = 360)
@Composable
fun ColorCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) { ColorCatalog() }
}

@Preview(name = "Catalog - surfaces - light", widthDp = 360)
@Composable
fun SurfaceCatalogLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) { SurfaceCatalog() }
}

@Preview(name = "Catalog - surfaces - dark", widthDp = 360)
@Composable
fun SurfaceCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) { SurfaceCatalog() }
}

@Preview(name = "Catalog - type - light", widthDp = 360)
@Composable
fun TypeCatalogLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) { TypeCatalog() }
}

@Preview(name = "Catalog - type - dark", widthDp = 360)
@Composable
fun TypeCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) { TypeCatalog() }
}

@Preview(name = "Catalog - layout - light", widthDp = 360)
@Composable
fun LayoutCatalogLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) { LayoutCatalog() }
}

@Preview(name = "Catalog - layout - dark", widthDp = 360)
@Composable
fun LayoutCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) { LayoutCatalog() }
}

@Preview(name = "Catalog - components - light", widthDp = 360)
@Composable
fun ComponentCatalogLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) { ComponentCatalog() }
}

@Preview(name = "Catalog - components - dark", widthDp = 360)
@Composable
fun ComponentCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) { ComponentCatalog() }
}

@Preview(name = "Catalog - celebration - light", widthDp = 360, heightDp = CELEBRATION_SHEET_HEIGHT_DP)
@Composable
fun CelebrationCatalogLightPreview() {
    // Motion off, so the sheet captures the same pixels every run — the contract every
    // animated `:core:ui` component holds to. See `LocalReducedMotion`.
    TitanTheme(theme = ThemePreference.LIGHT, reducedMotion = true) { CelebrationCatalog() }
}

@Preview(name = "Catalog - celebration - dark", widthDp = 360, heightDp = CELEBRATION_SHEET_HEIGHT_DP)
@Composable
fun CelebrationCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, reducedMotion = true) { CelebrationCatalog() }
}

@Preview(name = "Catalog - choices - light", widthDp = 360)
@Composable
fun ChoiceCatalogLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) { ChoiceCatalog() }
}

@Preview(name = "Catalog - choices - dark", widthDp = 360)
@Composable
fun ChoiceCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) { ChoiceCatalog() }
}

@Preview(name = "Catalog - plates - light", widthDp = 360)
@Composable
fun PlateCatalogLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) { PlateCatalog() }
}

@Preview(name = "Catalog - plates - dark", widthDp = 360)
@Composable
fun PlateCatalogDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) { PlateCatalog() }
}

/**
 * Tall enough for the whole sheet.
 *
 * Nine swatches, a bar and three rows of marks run well past a device height, and a `@Preview`
 * clips what does not fit without saying so — the trap the note at the top of this file
 * describes, and the reason `ChartCatalog` names a height too.
 */
private const val CELEBRATION_SHEET_HEIGHT_DP = 640

/** Part-way into a level, so the bar shows both its fill and its track. */
private const val CATALOG_LEVEL_PROGRESS = 0.43f

/** The real allowance, so the sheet re-records if the mechanic is ever retuned. */
private val CATALOG_ALLOWANCE = StreakProtection.MONTHLY_ALLOWANCE
