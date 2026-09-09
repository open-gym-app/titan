package com.opengym.feature.history.plate.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.format.formatCount
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.format.plateIncrement
import com.opengym.core.ui.format.weightToMilligrams
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R

/**
 * The two stored things the answer depends on: the bar, and what is on the rack.
 *
 * ### Steppers and chips, and no text field
 *
 * Every control here commits the moment it is pressed, which is why none of them is a field: a
 * bar weight typed a character at a time would be written as `2` on the way to `20`, and the
 * settings screen answers that with a dialog and a Save button. That is the right shape *there*,
 * where the row is one of twelve; it is the wrong one here, where the whole point is that the
 * barbell behind the sheet redraws as you change your mind about what you own. The exotic bar —
 * a 7.5 kg technique bar, a 30 kg axle — is still typeable in Settings.
 *
 * ### Sizes are switched off, not removed
 *
 * A count of zero keeps the row, so the size can be switched back on. That is also what makes
 * this editor complete without an "add a size" flow: the rack it starts from is the standard set
 * for the user's unit system, and every plate on it is one press away.
 *
 * ### A failed write is said in here, not behind here
 *
 * Every control on this sheet writes to the settings store, so this is where a store that cannot
 * be written is discovered — and a sheet is its own window, so the screen's snackbar would be
 * drawn underneath it and time out unseen. [message] is that failure, rendered above the controls
 * that raised it.
 *
 * @param barWeight the empty bar, in milligrams.
 * @param inventory the rack as it stands — the stored one, or the standard set.
 * @param message what went wrong, or **null** when nothing has. See above.
 * @param onReset forget the stored rack. Not "write today's default", which would then stop
 *   following a change of unit system; see `UserPreferences.plateInventory`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentSheet(
    barWeight: Long,
    inventory: PlateInventory,
    onBarWeightChange: (Long) -> Unit,
    onPairsChange: (weight: Long, pairs: Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
        // A half-expanded stop would cut the rack in half, which is the one thing this sheet
        // exists not to do.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        EquipmentPanel(
            barWeight = barWeight,
            inventory = inventory,
            onBarWeightChange = onBarWeightChange,
            onPairsChange = onPairsChange,
            onReset = onReset,
            message = message,
        )
    }
}

/**
 * The sheet's contents, without the sheet.
 *
 * Split out for the reason `LibraryFilterPanel` is: what is worth testing here is which rows are
 * drawn and what pressing one reports, and a `ModalBottomSheet`'s animation and its own window
 * are neither — they are also what makes a click in a test land nowhere.
 */
@Composable
fun EquipmentPanel(
    barWeight: Long,
    inventory: PlateInventory,
    onBarWeightChange: (Long) -> Unit,
    onPairsChange: (weight: Long, pairs: Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = OpenGymTheme.spacing.screen)
            .padding(bottom = OpenGymTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        // At the top, where the eye starts, rather than beside the control that failed: the
        // sheet's writes all fail for the same reason, and a per-row error would have to guess
        // which press the store was refusing.
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Eyebrow(text = stringResource(R.string.plate_equipment_bar))
        BarWeightRow(barWeight = barWeight, onChange = onBarWeightChange)

        Eyebrow(
            text = stringResource(R.string.plate_equipment_plates),
            modifier = Modifier.padding(top = OpenGymTheme.spacing.md),
        )
        inventory.stock.forEachIndexed { index, plate ->
            if (index > 0) HairlineDivider()
            PlateRow(
                plate = plate,
                rank = index,
                onChange = { pairs -> onPairsChange(plate.weight, pairs) },
            )
        }

        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.plate_equipment_reset))
        }
    }
}

/**
 * The bar: three presets, and a stepper for everything between them.
 *
 * The presets are two lists rather than one converted list, for the reason the settings screen
 * gives: a bar is a manufactured object and its weight is a round number in the system it was
 * made for. Converting the metric set would offer an American lifter a 44.09 lb bar.
 */
@Composable
private fun BarWeightRow(
    barWeight: Long,
    onChange: (Long) -> Unit,
) {
    val unitSystem = LocalUnitSystem.current
    val step = weightToMilligrams(unitSystem.plateIncrement, unitSystem)

    Stepper(
        value = formatWeight(barWeight),
        // Never to zero: `setBarWeight` refuses it, so a button that could reach it would be a
        // button that throws. See `UserPreferences.barWeight`.
        //
        // Clamped to the bar's own weight and not to `step`, because Settings can store a bar
        // lighter than one increment — a 2 kg kids' bar, against a 2.5 kg step — and clamping to
        // `step` would make "lighter" hand back something *heavier* than what is on screen. The
        // floor is whichever is smaller, so the press is a no-op at the bottom rather than a
        // reversal.
        onDecrease = { onChange((barWeight - step).coerceAtLeast(minOf(barWeight, step))) },
        onIncrease = { onChange(barWeight + step) },
        decreaseDescription = stringResource(R.string.plate_bar_lighter),
        increaseDescription = stringResource(R.string.plate_bar_heavier),
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
        val presets = when (unitSystem) {
            UnitSystem.METRIC -> METRIC_BARS
            UnitSystem.IMPERIAL -> IMPERIAL_BARS
        }
        presets.forEach { preset ->
            val milligrams = weightToMilligrams(preset, unitSystem)
            FilterChip(
                selected = barWeight == milligrams,
                onClick = { onChange(milligrams) },
                label = {
                    Text(
                        text = formatWeight(milligrams, unitSystem),
                        style = OpenGymTheme.typography.numericSmall,
                    )
                },
            )
        }
    }
}

/** One size on the rack: its colour in the diagram, its weight, and how many pairs of it. */
@Composable
private fun PlateRow(
    plate: PlateStock,
    rank: Int,
    onChange: (Int) -> Unit,
) {
    val tone = OpenGymTheme.plateColors.toneFor(rank)
    val weight = formatWeight(plate.weight)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        Box(
            modifier = Modifier
                .size(SWATCH_SIZE)
                .background(tone.fill, RoundedCornerShape(SWATCH_RADIUS))
                .border(
                    width = HAIRLINE_WIDTH,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(SWATCH_RADIUS),
                ),
        )
        Text(
            text = weight,
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Stepper(
            value = formatCount(plate.pairs),
            onDecrease = { onChange((plate.pairs - 1).coerceAtLeast(0)) },
            onIncrease = { onChange((plate.pairs + 1).coerceAtMost(PlateStock.MAX_PAIRS)) },
            decreaseDescription = stringResource(R.string.plate_pairs_fewer, weight),
            increaseDescription = stringResource(R.string.plate_pairs_more, weight),
        )
    }
}

/**
 * A `−`, a figure and a `+`.
 *
 * The bounds live at the call sites rather than here: each one is a rule about the value being
 * edited — a bar cannot weigh nothing, a rack cannot hold more than [PlateStock.MAX_PAIRS] of a
 * size — and a stepper that knew them would be a stepper with two of somebody else's invariants
 * in it. The buttons stay enabled and clamp, which keeps a press from being a no-op the user
 * cannot see the reason for.
 */
@Composable
private fun Stepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseDescription: String,
    increaseDescription: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDecrease) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = decreaseDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // A fixed column, so the two buttons do not shuffle sideways as the figure between
            // them changes width. Tabular figures do the rest — see `OpenGymTypography`.
            modifier = Modifier.width(STEPPER_VALUE_WIDTH),
        )
        IconButton(onClick = onIncrease) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = increaseDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The bars people own, per unit system. See [BarWeightRow]. */
private val METRIC_BARS = listOf(20.0, 15.0, 10.0)
private val IMPERIAL_BARS = listOf(45.0, 35.0, 25.0)

/** Wide enough for `102.5 kg`, which is the widest thing either stepper shows. */
private val STEPPER_VALUE_WIDTH = 84.dp

private val SWATCH_SIZE = 16.dp
private val SWATCH_RADIUS = 3.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

/** The panel rather than the sheet: a `@Preview` renders no modal window. */
@Composable
private fun EquipmentCatalogue() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        EquipmentPanel(
            barWeight = PREVIEW_BAR_WEIGHT,
            // The standard rack with the 20s taken off it, which is what a real gym's editor
            // looks like once somebody has been honest about their garage.
            inventory = PlateInventory.METRIC.withPairs(PREVIEW_MISSING_SIZE, 0),
            onBarWeightChange = {},
            onPairsChange = { _, _ -> },
            onReset = {},
        )
    }
}

@Preview(name = "Bar & plates", widthDp = 360, heightDp = 640)
@Composable
fun EquipmentPanelPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { EquipmentCatalogue() }
}

@Preview(name = "Bar & plates, dark", widthDp = 360, heightDp = 640)
@Composable
fun EquipmentPanelDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { EquipmentCatalogue() }
}

/** A 20 kg Olympic bar, and the 20 kg plates nobody's garage has a pair of. */
private const val PREVIEW_BAR_WEIGHT = 20_000_000L
private const val PREVIEW_MISSING_SIZE = 20_000_000L
