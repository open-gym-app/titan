package com.titan.feature.progression.body.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.StatTile
import com.titan.core.ui.format.formatPercentTenthsValue
import com.titan.core.ui.format.formatWeightValue
import com.titan.core.ui.format.percentSuffix
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.body.BodyReadings

/**
 * Body fat and lean mass, side by side.
 *
 * ### One is measured and the other is arithmetic
 *
 * Body fat is whatever the user entered — a caliper reading, a scale's guess, a DEXA scan.
 * Lean mass is neither stored nor entered: it is weight times the rest, computed on read from
 * the *same entry*, which is why it can be missing on a screen where both of its inputs are
 * showing. `BodyMeasurement.leanMass` carries that argument in full, and
 * `BodyMeasurementsUiState.readings` carries the consequence — the last lean mass is the last
 * one that could be computed, not this month's weight paired with March's body fat.
 *
 * ### Neither tile is accented
 *
 * `StatTile` offers the accent for the one figure on a screen that is a *result* rather than a
 * fact. Neither of these is: the weight card above them is what this screen is about, and
 * spending the accent twice more here is how it stops meaning anything on any of the three.
 */
@Composable
fun CompositionTiles(
    readings: BodyReadings,
    modifier: Modifier = Modifier,
) {
    val unitSystem = LocalUnitSystem.current
    val noReading = stringResource(R.string.body_no_reading)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        StatTile(
            label = stringResource(R.string.body_fat_label),
            value = readings.bodyFat?.let(::formatPercentTenthsValue) ?: noReading,
            // The unit goes with the figure. A tile reading `— %` claims a percentage of
            // something was measured and rounded to nothing.
            unit = readings.bodyFat?.let { percentSuffix },
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(R.string.body_lean_mass_label),
            value = readings.leanMass?.let { formatWeightValue(it, unitSystem) } ?: noReading,
            unit = readings.leanMass?.let { unitSystem.weightSuffix },
            modifier = Modifier.weight(1f),
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Composition tiles", showBackground = true, widthDp = 360)
@Composable
fun CompositionTilesPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        CompositionTiles(readings = BodyReadings(bodyFat = 142, leanMass = 67_300_000L))
    }
}

@Preview(name = "Composition tiles - nothing measured", showBackground = true, widthDp = 360)
@Composable
fun CompositionTilesEmptyPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        CompositionTiles(readings = BodyReadings())
    }
}
