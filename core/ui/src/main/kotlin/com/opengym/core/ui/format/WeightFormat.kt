package com.opengym.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.milligramsPerUnit
import com.opengym.core.ui.theme.LocalUnitSystem
import kotlin.math.roundToLong

/**
 * Weights, from the milligrams everything is stored in to the string a user reads — and
 * back again.
 *
 * ### Why milligrams reach the UI at all
 *
 * Because they are what a weight *is* here: `WorkoutSet.weight` is milligrams, canonically
 * and in both unit systems, so that flipping [UnitSystem] never rewrites a row (see
 * `UnitSystem`'s own note). Conversion is therefore a rendering step and belongs on the way
 * to the screen, which is here — not in a second, imperial copy of the data.
 *
 * ### Two decimals, and no trailing zero
 *
 * The precision is set by the smallest plate anyone racks, because the acceptance criterion
 * is that a weight entered in either system reads back unchanged. Metric micro-plates stop
 * at 0.5 kg, which one decimal covers; **imperial ones go to 1.25 lb, which it does not** —
 * at one decimal a 46.25 lb load reads back as 46.3, a weight the user did not lift and
 * cannot make from their plates. So: two decimals, in both systems, so the grid of
 * loadable weights survives the round trip.
 *
 * The trailing zeros still go, because `minimumFractionDigits` is 0 — everyday lifts are
 * whole numbers and read as `100`, `45`, `20`. Only a *converted* weight spends the extra
 * glyphs: an imperial user reading a 20 kg bar sees `44.09 lb`, which is what it weighs.
 *
 * ### The display is never snapped to a plate increment
 *
 * "Respect plate increments" means the format must be able to *express* 2.5 kg and 5 lb
 * steps, not that it should round to them. Rounding a 20 kg bar to the nearest 5 lb would
 * put `45 lb` on screen for a bar that weighs 44.09 — a number the user would then fail to
 * reproduce on the rack. Snapping belongs to the things that *choose* a weight, the plate
 * calculator (#M4-21) and the +/- steppers, which is what [plateIncrement] and
 * [roundToPlateIncrement] are for. A formatter reports; it does not decide.
 *
 * ### The unit system is read, not passed
 *
 * Each formatter has a `@Composable` overload that takes the milligrams alone and reads
 * [LocalUnitSystem] itself, and that is the one a screen should call: the alternative is
 * every composable between the preference and the number taking a [UnitSystem] it does
 * nothing with but pass on, and the first one that forgets renders kilograms to an imperial
 * user with nothing on screen to say so. Because [LocalUnitSystem] is a non-static
 * composition local, changing the setting recomposes every one of these and no screen has
 * to be revisited. The explicit overloads remain for the call sites that genuinely hold a
 * unit system that is not the ambient one — a preview, a test, or the settings row that is
 * *editing* the preference.
 */

/** How many decimals a weight is shown to. See the note above. */
private const val WEIGHT_FRACTION_DIGITS = 2

/** `kg` or `lb`, for a label that shows the unit without a number in front of it. */
val UnitSystem.weightSuffix: String
    get() = when (this) {
        UnitSystem.METRIC -> "kg"
        UnitSystem.IMPERIAL -> "lb"
    }

/**
 * The step a loaded bar actually moves in: a pair of 1.25 kg plates, or a pair of 2.5 lb
 * ones. Expressed in the display unit, because that is the unit the plates are stamped in.
 *
 * The two are not conversions of each other and must not be made so — a rack is stocked in
 * one system or the other, and 5 lb is 2.27 kg, which is not a plate that exists.
 */
val UnitSystem.plateIncrement: Double
    get() = when (this) {
        UnitSystem.METRIC -> 2.5
        UnitSystem.IMPERIAL -> 5.0
    }

/**
 * The finest step a well-stocked gym can make, from its smallest micro-plates: 0.25 kg
 * plates in pairs, or 0.625 lb ones.
 *
 * This is the number [WEIGHT_FRACTION_DIGITS] is chosen against — every value on this grid
 * has to survive being displayed and typed back in. `WeightFormatTest` asserts that, so
 * lowering the precision fails a test that says why.
 */
val UnitSystem.smallestPlateIncrement: Double
    get() = when (this) {
        UnitSystem.METRIC -> 0.5
        UnitSystem.IMPERIAL -> 1.25
    }

/**
 * [milligrams] rounded to the nearest weight this system's plates can make.
 *
 * For the call sites that pick a weight rather than report one — a suggested next load, a
 * `+`/`-` stepper, the plate calculator. Rounding happens in the display unit, so a metric
 * lifter lands on 82.5 kg and an imperial one on 185 lb, rather than both landing on
 * whichever the other's plates would have made.
 */
fun roundToPlateIncrement(
    milligrams: Long,
    unitSystem: UnitSystem,
    increment: Double = unitSystem.plateIncrement,
): Long {
    require(increment > 0) { "the increment must be positive, was $increment" }
    val perUnit = unitSystem.milligramsPerUnit
    val steps = (milligrams / perUnit / increment).roundToLong()
    return (steps * increment * perUnit).roundToLong()
}

/**
 * [milligrams] in [unitSystem], as a number with no unit — for a call site that draws the
 * suffix itself, in its own colour or type role.
 */
fun formatWeightValue(milligrams: Long, unitSystem: UnitSystem): String =
    formatNumber(milligrams / unitSystem.milligramsPerUnit, WEIGHT_FRACTION_DIGITS)

/**
 * [milligrams] in [unitSystem], with the unit: `20 kg`, `44.09 lb`, `2.5 kg`.
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
 * A user's typed weight back into storage: [text] read as [unitSystem]'s unit, as
 * milligrams, or null when it is not a number.
 *
 * The inverse of [formatWeightValue], and the round trip holds in both directions:
 * anything this returns formats back to the text that produced it, and anything
 * [formatWeightValue] renders parses back to a weight that renders identically. That is
 * what lets the bar-weight field be seeded with the stored value and saved untouched
 * without drifting a gram per edit.
 *
 * **Null means malformed, not out of range.** A negative weight, a 40-tonne one and a
 * plausible one all parse; deciding which are allowed is `SetValidator`'s job, and it is
 * the one that can say *why*. So a form does this:
 *
 * ```kotlin
 * val milligrams = parseWeightMilligrams(text, unitSystem)
 *     ?: return ValidationResult.Invalid(getString(R.string.weight_not_a_number))
 * SetValidator.validate(set.copy(weight = milligrams), exercise.type)
 * ```
 */
fun parseWeightMilligrams(text: String, unitSystem: UnitSystem): Long? =
    parseNumber(text)?.let { weightToMilligrams(it, unitSystem) }

/**
 * [value] of [unitSystem]'s unit as milligrams — the conversion without the parsing.
 *
 * For the call sites that already hold a number rather than a string: a preset chip, a
 * seeded default, a stepper's next value. Text goes through [parseWeightMilligrams]
 * instead, which is this function with a locale-aware reader in front of it.
 */
fun weightToMilligrams(value: Double, unitSystem: UnitSystem): Long =
    (value * unitSystem.milligramsPerUnit).roundToLong()

/** [text] in the ambient [LocalUnitSystem], as milligrams. See [parseWeightMilligrams]. */
@Composable
@ReadOnlyComposable
fun parseWeightMilligrams(text: String): Long? =
    parseWeightMilligrams(text, LocalUnitSystem.current)
