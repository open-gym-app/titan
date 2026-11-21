package com.titan.core.ui.format

import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.titan.core.domain.model.MILLIMETRES_PER_CENTIMETRE
import com.titan.core.domain.model.MILLIMETRES_PER_INCH
import com.titan.core.domain.model.UnitSystem
import com.titan.core.ui.theme.LocalUnitSystem
import kotlin.math.roundToInt

/*
 * Circumferences, from the millimetres they are stored in to the string a user reads — and
 * back again.
 *
 * ### Not a distance, and not a weight
 *
 * [formatDistance] already turns centimetres into kilometres and miles, and it is the wrong
 * formatter for a chest measurement: it picks its unit from the magnitude, so a 102 cm chest
 * would come back as `1.02 m`, and its imperial branch answers in yards. A body is measured
 * with a tape, in the two units tapes are printed in, and that is the whole of this file —
 * `cm` for a metric user and `in` for an imperial one, with no threshold in between.
 *
 * The unit system is read rather than passed, exactly as [formatWeight] reads it, and for the
 * same reason: the `@Composable` overloads take [LocalUnitSystem] themselves so nothing in
 * between has to carry a [UnitSystem] it does nothing with. The explicit overloads stay for a
 * preview, a test, or a form that is editing in a system that is not the ambient one.
 *
 * ### One decimal, in both systems
 *
 * The precision is set by the round trip through storage, which is an **integer millimetre**
 * — see `BodyMeasurement`. At one decimal a metric value is exact (a millimetre *is* a tenth
 * of a centimetre), and an imperial one lands within a twentieth of an inch, which is finer
 * than any tape is read to and close enough that the string survives being displayed, typed
 * back and displayed again. `LengthFormatTest` asserts that in both directions.
 *
 * Two decimals — the number [formatWeightValue] uses — would be spent on a precision the
 * measurement does not have: a tape around a thigh moves by more than a hundredth of an inch
 * depending on how hard it is pulled, and `57.48 cm` claims otherwise.
 */

/** How many decimals a circumference is shown to. See the note above. */
private const val LENGTH_FRACTION_DIGITS = 1

/** The CLDR unit a tape is marked in on this system. The twin of [UnitSystem.weightUnit]. */
val UnitSystem.lengthUnit: MeasureUnit
    get() = when (this) {
        UnitSystem.METRIC -> MeasureUnit.CENTIMETER
        UnitSystem.IMPERIAL -> MeasureUnit.INCH
    }

/**
 * `cm` or `in`, for a label that shows the unit without a number in front of it — `po` to a
 * French reader, `pol.` to a Brazilian one.
 *
 * From CLDR rather than a literal, as [UnitSystem.weightSuffix] is and for the same reason.
 * The inch is the unit that makes the point: unlike `kg` it is not the same two letters
 * everywhere, and this is the field label a body measurement is typed against.
 */
val UnitSystem.lengthSuffix: String
    get() = unitSymbol(lengthUnit)

/**
 * How many millimetres make one of this system's tape units.
 *
 * The imperial factor is exact by definition — an inch has been 25.4 mm since 1959 — so
 * neither direction of the conversion carries an approximation of its own.
 */
val UnitSystem.millimetresPerLengthUnit: Double
    get() = when (this) {
        UnitSystem.METRIC -> MILLIMETRES_PER_CENTIMETRE
        UnitSystem.IMPERIAL -> MILLIMETRES_PER_INCH
    }

/**
 * [millimetres] in [unitSystem], as a number with no unit — for a call site that draws the
 * suffix itself, in its own colour or type role.
 */
fun formatLengthValue(
    millimetres: Int,
    unitSystem: UnitSystem,
): String = formatNumber(millimetres / unitSystem.millimetresPerLengthUnit, LENGTH_FRACTION_DIGITS)

/** [millimetres] in [unitSystem], with the unit: `102 cm`, `40.2 in`. */
fun formatLength(
    millimetres: Int,
    unitSystem: UnitSystem,
): String =
    formatMeasure(
        value = millimetres / unitSystem.millimetresPerLengthUnit,
        unit = unitSystem.lengthUnit,
        maxFractionDigits = LENGTH_FRACTION_DIGITS,
    )

/** [millimetres] in the ambient [LocalUnitSystem], with the unit. See the note above. */
@Composable
@ReadOnlyComposable
fun formatLength(millimetres: Int): String = formatLength(millimetres, LocalUnitSystem.current)

/** [millimetres] in the ambient [LocalUnitSystem], without the unit. See the note above. */
@Composable
@ReadOnlyComposable
fun formatLengthValue(millimetres: Int): String = formatLengthValue(millimetres, LocalUnitSystem.current)

/**
 * A user's typed circumference back into storage: [text] read as [unitSystem]'s tape unit, as
 * millimetres, or null when it is not a number.
 *
 * **Null means malformed, not out of range**, the rule [parseWeightMilligrams] states in full:
 * a negative measurement and a five-metre one both parse, and refusing them is
 * `BodyMeasurementValidator`'s job, which can say why.
 */
fun parseLengthMillimetres(
    text: String,
    unitSystem: UnitSystem,
): Int? = parseNumber(text)?.let { lengthToMillimetres(it, unitSystem) }

/**
 * [value] of [unitSystem]'s tape unit as millimetres — the conversion without the parsing.
 *
 * For the call sites that already hold a number rather than a string: a seeded default, a
 * value carried across a unit change.
 */
fun lengthToMillimetres(
    value: Double,
    unitSystem: UnitSystem,
): Int = (value * unitSystem.millimetresPerLengthUnit).roundToInt()

/** [text] in the ambient [LocalUnitSystem], as millimetres. See [parseLengthMillimetres]. */
@Composable
@ReadOnlyComposable
fun parseLengthMillimetres(text: String): Int? = parseLengthMillimetres(text, LocalUnitSystem.current)
