package com.titan.core.ui.format

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.UnitSystem
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Every formatter's output, in both unit systems.
 *
 * The twin of `theme/ThemeCatalog.kt`, and in `src/test` for the same reason: it is
 * documentation of the design system, not a component anything is meant to use, so it must
 * not ship in the library. It is still an ordinary set of `@Preview`s — open this file in
 * Android Studio to see what a weight or a rest looks like before writing the screen that
 * shows one, and the Roborazzi plugin captures each as a screenshot reference, which turns
 * an accidental change to a rounding rule into a reviewable image diff.
 *
 * **Not one composable takes a `UnitSystem`.** Every row below reads `LocalUnitSystem`
 * through the `@Composable` formatter overloads, which is the whole claim #M4-17 makes: the
 * metric and imperial previews differ only in the argument to `TitanTheme`, and that is
 * the same argument `:app` passes from `UserPreferencesRepository`.
 *
 * The canonical values are shared with the round-trip tests where it matters, so a number
 * here is a number some assertion also pins rather than one somebody typed to look plausible.
 */

/** A 20 kg Olympic bar, in milligrams. */
private const val OLYMPIC_BAR = 20_000_000L

/** A working set: 102.5 kg, on the metric plate grid. */
private const val WORKING_SET = 102_500_000L

/** A dumbbell at the smallest fractional plate, to show the display precision. */
private const val MICRO_PLATE = 2_500_000L

/** A session's volume total: 4,820 kg, the wireframes' summary tile. */
private const val SESSION_VOLUME = 4_820_000_000L

/** An all-time total, to show the grouping hold up past a million. */
private const val CAREER_VOLUME = 1_240_000_000_000L

/** A 5.2 km treadmill run, in centimetres. */
private const val CARDIO_RUN = 520_000L

/** A 400 m repeat — the case that reads as `0.4 km` if the short unit is skipped. */
private const val TRACK_REPEAT = 40_000L

/** A 25 m pool length. */
private const val POOL_LENGTH = 2_500L

/** A marathon, to the metre. */
private const val MARATHON = 4_219_500L

/** A 102 cm chest, in the millimetres the schema stores — `LengthFormatTest`'s own figure. */
private const val CHEST_MM = 1_020

/** A waist that is whole in neither system: 82.3 cm, 32.4 in. */
private const val WAIST_MM = 823

/** A thigh: whole in centimetres, and imperial to within a hundredth — so `24 in`, not `24.0`. */
private const val THIGH_MM = 610

/** A wrist — the small end, where a tenth of an inch is a visible share of the figure. */
private const val WRIST_MM = 170

/** Weights, volumes, distances and circumferences, all reading the ambient unit system. */
@Composable
fun FormatCatalog(modifier: Modifier = Modifier) {
    CatalogSheet(modifier) {
        FormatSection("Weight") {
            FormatRow("Olympic bar", formatWeight(OLYMPIC_BAR))
            FormatRow("Working set", formatWeight(WORKING_SET))
            // The imperial preview renders this as 5.51 lb, which is the point: a metric
            // 2.5 kg plate is not a plate an imperial rack has, and the formatter reports
            // what the weight *is* rather than rounding it onto the nearest 5 lb the user
            // would then fail to load.
            FormatRow("Metric micro-plate", formatWeight(MICRO_PLATE))
            FormatRow("Bodyweight set", formatWeight(0))
        }
        FormatSection("Volume") {
            // The hero figure on a session summary: the value and its unit in different type
            // roles, which is why the formatters come apart as well as together.
            HeroRow(
                value = formatVolumeValue(SESSION_VOLUME),
                unit = LocalUnitSystem.current.weightSuffix,
            )
            FormatRow("This session", formatVolume(SESSION_VOLUME))
            FormatRow("All time", formatVolume(CAREER_VOLUME))
        }
        FormatSection("Distance") {
            FormatRow("Treadmill run", formatDistance(CARDIO_RUN))
            FormatRow("Track repeat", formatDistance(TRACK_REPEAT))
            FormatRow("Pool length", formatDistance(POOL_LENGTH))
            FormatRow("Marathon", formatDistance(MARATHON))
        }
        FormatSection("Circumference") {
            // A tape, not a distance: `cm` and `in` with no threshold in between, where the
            // rows above would have answered `1.02 m` and `1.12 yd`. The imperial column is
            // the one worth reading — one decimal is where a millimetre store and an inch
            // tape meet, and a change to that precision moves every figure here.
            FormatRow("Chest", formatLength(CHEST_MM))
            FormatRow("Waist", formatLength(WAIST_MM))
            FormatRow("Thigh", formatLength(THIGH_MM))
            FormatRow("Wrist", formatLength(WRIST_MM))
        }
        FormatSection("Duration") {
            // No unit system to read: a minute is a minute in both, so these two rows are
            // identical across the metric and imperial previews. That is the point of
            // capturing them here rather than trusting it.
            FormatRow("Rest countdown", 90.seconds.formatAsClock())
            FormatRow("Rest, final seconds", 7.seconds.formatAsClock())
            FormatRow("Session elapsed", (52.minutes + 14.seconds).formatAsElapsed())
            FormatRow("Long session", (2.hours + 3.minutes).formatAsElapsed())
            // Deliberately unlike the two above: a template's estimate is a pacing figure,
            // not a measurement, and the tilde and unit letters are what say so.
            FormatRow("Template estimate", (53.minutes + 20.seconds).formatAsEstimate())
            FormatRow("Long template estimate", (1.hours + 34.minutes).formatAsEstimate())
        }
    }
}

/** The summary tile: an eyebrow, a figure in the accent, and the unit under it. */
@Composable
private fun HeroRow(
    value: String,
    unit: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TitanTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Text(
            text = value,
            style = TitanTheme.typography.numericLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Eyebrow(text = unit)
    }
}

/** A labelled value, at the density a settings or summary card uses. */
@Composable
private fun FormatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = TitanTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FormatSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        Eyebrow(text = title)
        TitanCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = TitanTheme.spacing.xl)) {
                content()
            }
        }
    }
}

@Composable
private fun CatalogSheet(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // `fillMaxSize`, so the sheet paints its background over the whole preview frame rather
    // than only behind the rows — otherwise a dark capture is a dark card on white paper.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
            content = content,
        )
    }
}

/*
 * Four previews rather than two: the unit system decides the numbers and the theme decides
 * the colours, and a rounding change that only showed up in dark mode would otherwise go
 * uncaptured.
 *
 * `heightDp` is set because a `@Preview` renders at the device's height and silently clips
 * whatever runs past it — the same trap that cost `ThemeCatalog` its type scale, and the one
 * that had cost this file its two estimate rows until the circumference section forced the
 * frame open. **Adding a row means checking the recorded image still ends in paper**: a
 * formatter that falls off the bottom is one no diff can report on.
 */

@Preview(name = "Formatters - metric - light", widthDp = 360, heightDp = 1000)
@Composable
fun FormatCatalogMetricLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, unitSystem = UnitSystem.METRIC) { FormatCatalog() }
}

@Preview(name = "Formatters - metric - dark", widthDp = 360, heightDp = 1000)
@Composable
fun FormatCatalogMetricDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, unitSystem = UnitSystem.METRIC) { FormatCatalog() }
}

@Preview(name = "Formatters - imperial - light", widthDp = 360, heightDp = 1000)
@Composable
fun FormatCatalogImperialLightPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, unitSystem = UnitSystem.IMPERIAL) { FormatCatalog() }
}

@Preview(name = "Formatters - imperial - dark", widthDp = 360, heightDp = 1000)
@Composable
fun FormatCatalogImperialDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, unitSystem = UnitSystem.IMPERIAL) { FormatCatalog() }
}
