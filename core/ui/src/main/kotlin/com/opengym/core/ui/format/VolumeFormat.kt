package com.opengym.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.milligramsPerUnit
import com.opengym.core.ui.theme.LocalUnitSystem

/*
 * Training volume — `weight x reps`, summed — as the headline figure on a summary card.
 *
 * The same milligrams as a weight, so this is `formatWeight` with two deliberate
 * differences, both of which follow from a volume being *read* and never typed.
 *
 * ### Grouped, because the number is large
 *
 * A session total is four or five digits and an all-time one is seven, and `4820` does not
 * read as "nearly five tonnes" the way `4,820` does. The separator is the locale's, so a
 * French user gets `4 820` and a German `4.820`; that it is a *narrow no-break space* in
 * some locales is exactly why this goes through `NumberFormat` and not through a hand-rolled
 * insert-a-comma-every-three-digits, which would also produce `1,234` in a locale where
 * that means 1.234.
 *
 * A weight, by contrast, is ungrouped: it is the value a bar-weight or set field is seeded
 * with, and a text field the user has to clear a thousands separator out of is a worse
 * trade than a legible four-digit weight nobody logs anyway.
 *
 * ### Whole units, because the tenths are noise
 *
 * 4,820.4 kg is 12 sets of squats and a rounding artefact. The tenth of a kilogram that
 * matters on a single set stops meaning anything once a hundred of them are added up, and
 * it costs a column of width in the one place — a stat tile — where the figure is set in
 * `numericLarge` and the width is already spoken for.
 */

/** How many decimals a volume total is shown to. See the note above. */
private const val VOLUME_FRACTION_DIGITS = 0

/**
 * [milligrams] of volume in [unitSystem], grouped, with no unit — for the stat tile that
 * draws `VOLUME`, the figure and `kg` as three separate type roles.
 */
fun formatVolumeValue(
    milligrams: Long,
    unitSystem: UnitSystem,
): String =
    formatNumber(
        value = milligrams / unitSystem.milligramsPerUnit,
        maxFractionDigits = VOLUME_FRACTION_DIGITS,
        grouping = true,
    )

/** [milligrams] of volume in [unitSystem], with the unit: `4,820 kg`, `10,626 lb`. */
fun formatVolume(
    milligrams: Long,
    unitSystem: UnitSystem,
): String = "${formatVolumeValue(milligrams, unitSystem)} ${unitSystem.weightSuffix}"

/** [milligrams] of volume in the ambient [LocalUnitSystem], with the unit. */
@Composable
@ReadOnlyComposable
fun formatVolume(milligrams: Long): String = formatVolume(milligrams, LocalUnitSystem.current)

/** [milligrams] of volume in the ambient [LocalUnitSystem], without the unit. */
@Composable
@ReadOnlyComposable
fun formatVolumeValue(milligrams: Long): String = formatVolumeValue(milligrams, LocalUnitSystem.current)
