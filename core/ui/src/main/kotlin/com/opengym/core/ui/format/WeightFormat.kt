package com.opengym.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.theme.LocalUnitSystem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Weights, from the milligrams everything is stored in to the string a user reads.
 *
 * ### Why milligrams reach the UI at all
 *
 * Because they are what a weight *is* here: `WorkoutSet.weight` is milligrams, canonically
 * and in both unit systems, so that flipping [UnitSystem] never rewrites a row (see
 * `UnitSystem`'s own note). Conversion is therefore a rendering step and belongs on the way
 * to the screen, which is here — not in a second, imperial copy of the data.
 *
 * ### One decimal, and no trailing zero
 *
 * A gym weight is meaningful to about a tenth of a unit — a 2.5 kg plate is the smallest
 * thing most racks own, and the smallest fractional plate anyone stocks is 0.5 kg — so a
 * second decimal is noise that costs a column of width on a 360dp screen. The trailing `.0`
 * goes for the same reason: `100 kg` is what a person would write, and `100.0 kg` reads as
 * an instrument reading rather than a lift.
 *
 * The number is formatted in the device's locale, so a French user sees `22,5` — the
 * numeric type role is monospaced with tabular figures, and a decimal comma occupies the
 * same cell as a decimal point.
 *
 * ### The unit system is read, not passed
 *
 * Each formatter has a `@Composable` overload that takes the milligrams alone and reads
 * [LocalUnitSystem] itself, and that is the one a screen should call: the alternative is
 * every composable between the preference and the number taking a [UnitSystem] it does
 * nothing with but pass on, and the first one that forgets renders kilograms to an imperial
 * user with nothing on screen to say so. The explicit overloads remain for the call sites
 * that genuinely hold a unit system that is not the ambient one — a preview, a test, or the
 * settings row that is *editing* the preference.
 *
 * **This is the minimum #M4-23 needs, not the whole of #M4-17.** Distances, paces, plate
 * math and the input side (parsing what a user types back into milligrams) land with #91,
 * which builds on these constants rather than restating them.
 */

/** One kilogram in milligrams. The unit `WorkoutSet.weight` is stored in. */
const val MILLIGRAMS_PER_KILOGRAM: Double = 1_000_000.0

/** One pound in milligrams, exactly: a pound is defined as 453.59237 g. */
const val MILLIGRAMS_PER_POUND: Double = 453_592.37

/** How many decimals a weight is shown to. See the note above. */
private const val WEIGHT_DECIMALS = 1

/** The tolerance under which a rounded weight is treated as whole, so its `.0` is dropped. */
private const val WHOLE_NUMBER_EPSILON = 0.05

/** `kg` or `lb`, for a label that shows the unit without a number in front of it. */
val UnitSystem.weightSuffix: String
    get() = when (this) {
        UnitSystem.METRIC -> "kg"
        UnitSystem.IMPERIAL -> "lb"
    }

/**
 * [milligrams] in [unitSystem], as a number with no unit — for a call site that draws the
 * suffix itself, in its own colour or type role.
 */
fun formatWeightValue(milligrams: Long, unitSystem: UnitSystem): String {
    val perUnit = when (unitSystem) {
        UnitSystem.METRIC -> MILLIGRAMS_PER_KILOGRAM
        UnitSystem.IMPERIAL -> MILLIGRAMS_PER_POUND
    }
    val value = milligrams / perUnit
    val rounded = (value * TENTHS).roundToLong() / TENTHS
    val decimals = if (abs(rounded - rounded.toLong()) < WHOLE_NUMBER_EPSILON) 0 else WEIGHT_DECIMALS
    return String.format(Locale.getDefault(), "%.${decimals}f", rounded)
}

/**
 * [milligrams] in [unitSystem], with the unit: `20 kg`, `44.1 lb`, `2.5 kg`.
 *
 * An ordinary space, deliberately: a non-breaking one would be typographically better but
 * makes every test and every accessibility label match on a character nobody types, which
 * has cost more than it has ever bought. Rows that could wrap constrain the text instead.
 */
fun formatWeight(milligrams: Long, unitSystem: UnitSystem): String =
    "${formatWeightValue(milligrams, unitSystem)} ${unitSystem.weightSuffix}"

/** [milligrams] in the ambient [LocalUnitSystem], with the unit. See the note above. */
@Composable
@ReadOnlyComposable
fun formatWeight(milligrams: Long): String = formatWeight(milligrams, LocalUnitSystem.current)

/** [milligrams] in the ambient [LocalUnitSystem], without the unit. See the note above. */
@Composable
@ReadOnlyComposable
fun formatWeightValue(milligrams: Long): String =
    formatWeightValue(milligrams, LocalUnitSystem.current)

/**
 * The user's typed weight back into storage: [value] in [unitSystem]'s unit, as milligrams.
 *
 * The exact inverse of [formatWeightValue] to within the rounding it does, and the reason
 * the bar-weight setting can round-trip through a text field without drifting a gram per
 * edit.
 */
fun weightToMilligrams(value: Double, unitSystem: UnitSystem): Long {
    val perUnit = when (unitSystem) {
        UnitSystem.METRIC -> MILLIGRAMS_PER_KILOGRAM
        UnitSystem.IMPERIAL -> MILLIGRAMS_PER_POUND
    }
    return (value * perUnit).roundToLong()
}

private const val TENTHS = 10.0
