package com.titan.core.ui.format

import android.icu.util.MeasureUnit
import com.titan.core.domain.model.NANOS_PER_SECOND
import com.titan.core.domain.model.PaceStyle
import com.titan.core.domain.model.UnitSystem
import kotlin.math.abs

/*
 * How fast something went, from the whole nanoseconds per centimetre a best pace is ranked on to
 * what a runner, a swimmer or a cyclist reads (#225).
 *
 * One stored figure, three readings — see [PaceStyle]. A pace is a clock reading per a stretch
 * of ground: `5:27 /km` for a run, `1:45 /100 m` for a swim. A speed is a number per hour:
 * `30.0 km/h` for a ride. The stretch and the hour both follow the unit system, as every other
 * figure here does: miles and hundreds of yards on an imperial phone, `mph` for a ride.
 *
 * ### Whole numbers all the way to the glyphs
 *
 * The pace arrives as an integer (see `paceOf`), every length it is converted through is an
 * exact integer count of tenths of a millimetre ([DistanceUnit.decimillimetres]), every division
 * rounds half up in integer arithmetic, and a speed is handed to the formatter as whole tenths.
 * Nothing is a binary fraction at any point, so a pace reads back the same on every device and
 * the only rounding is the one the reader sees: to the second, or to the tenth.
 *
 * Kept as a figure and a unit, as the record formatters are, because a stat tile sets the two in
 * different type roles and a row joins them through `figure_with_unit`. The improvement between
 * two paces is here too, because it has to be rounded the way the two figures it compares are —
 * a row reading `5:27` above `5:10` has to say `0:17`.
 */

/** A speed is shown in tenths: one decimal, always. */
private const val SPEED_SCALE = 1
private const val TENTHS_PER_UNIT = 10L

private const val SECONDS_PER_HOUR = 3_600L

/** Tenths of a millimetre in a centimetre: what turns a pace per centimetre into one per unit. */
private const val DECIMILLIMETRES_PER_CENTIMETRE = 100L

/** A swim's pace is read per this many of the system's short unit: 100 m, or 100 yd. */
private const val SWIM_STRETCH = 100L

/** What a pace is read *per*: the `/` of `/km`. A symbol, like the tilde on an estimate. */
private const val PER = "/"

/**
 * [nanosPerCentimetre] as [style] reads it on [unitSystem], with no unit: `5:27`, `1:45`, or
 * `30.0` for a speed.
 *
 * A pace is rounded to the whole second, which is what a running watch shows and is finer than
 * any GPS track or treadmill readout is accurate to. A speed keeps one decimal.
 */
fun formatPaceFigure(
    nanosPerCentimetre: Long,
    style: PaceStyle,
    unitSystem: UnitSystem,
): String =
    when (style) {
        PaceStyle.SPEED -> formatDecimal(speedTenths(nanosPerCentimetre, unitSystem), SPEED_SCALE)
        PaceStyle.PACE, PaceStyle.SWIM_PACE -> formatClockValue(secondsPer(nanosPerCentimetre, style, unitSystem))
    }

/**
 * What [formatPaceFigure] is measured in: `/km`, `/mi`, `/100 m`, `/100 yd`, `km/h` or `mph`.
 *
 * The unit symbols are CLDR's, as every unit here is — see `MeasureFormats.kt`. The hundred of a
 * swim's stretch is a number and goes through the number formatter with it, so it is written in
 * the locale's own digits.
 */
fun paceUnit(
    style: PaceStyle,
    unitSystem: UnitSystem,
): String =
    when (style) {
        PaceStyle.SPEED -> unitSymbol(unitSystem.speedUnit)
        PaceStyle.PACE -> PER + unitSystem.paceStretch.suffix
        PaceStyle.SWIM_PACE -> PER + formatWholeMeasure(SWIM_STRETCH, unitSystem.swimStretch.measureUnit)
    }

/**
 * How much faster [current] is than [previous], both in nanoseconds per centimetre, as [style]
 * reads it and without a sign: `0:17` for a pace, `1.5` for a speed.
 *
 * The difference of the two *displayed* figures rather than the displayed difference, so the row
 * does the arithmetic its reader would — `5:27` against `5:10` is `0:17`, whatever fraction of a
 * second each was rounded from. Which way the sign runs is the caller's to say: a faster pace is
 * a smaller one, and a faster speed a bigger one.
 */
fun formatPaceChange(
    previous: Long,
    current: Long,
    style: PaceStyle,
    unitSystem: UnitSystem,
): String =
    when (style) {
        PaceStyle.SPEED -> {
            val change = speedTenths(current, unitSystem) - speedTenths(previous, unitSystem)
            formatDecimal(abs(change), SPEED_SCALE)
        }

        PaceStyle.PACE, PaceStyle.SWIM_PACE -> {
            val change = secondsPer(previous, style, unitSystem) - secondsPer(current, style, unitSystem)
            formatClockValue(abs(change))
        }
    }

/**
 * Whole seconds per the stretch [style] reads a pace over — a kilometre or mile, or 100 m or
 * 100 yd — rounded half up.
 */
private fun secondsPer(
    nanosPerCentimetre: Long,
    style: PaceStyle,
    unitSystem: UnitSystem,
): Long {
    val stretch = when (style) {
        PaceStyle.SWIM_PACE -> unitSystem.swimStretch.decimillimetres * SWIM_STRETCH
        PaceStyle.PACE, PaceStyle.SPEED -> unitSystem.paceStretch.decimillimetres
    }
    return multiplyDivideRounded(nanosPerCentimetre, stretch, NANOS_PER_SECOND * DECIMILLIMETRES_PER_CENTIMETRE)
}

/**
 * Whole tenths of a kilometre or mile an hour, rounded half up.
 *
 * The hour in tenths of a nanosecond-centimetre, over the pace times the unit's length. A pace so
 * slow that the product would not fit in a `Long` — beyond 500 seconds a centimetre, which the
 * validator admits and nobody runs — is a speed far below a tenth, and reads as zero.
 */
private fun speedTenths(
    nanosPerCentimetre: Long,
    unitSystem: UnitSystem,
): Long {
    val unit = unitSystem.paceStretch.decimillimetres
    if (nanosPerCentimetre <= 0L || nanosPerCentimetre > Long.MAX_VALUE / unit) return 0L
    val hour = SECONDS_PER_HOUR * TENTHS_PER_UNIT * NANOS_PER_SECOND * DECIMILLIMETRES_PER_CENTIMETRE
    val divisor = nanosPerCentimetre * unit
    return (hour + divisor / 2) / divisor
}

/**
 * [value] × [multiplier] / [divisor], rounded half up, for non-negative operands — without the
 * product overflowing for any [value] a `Long` can hold, as long as [multiplier] × [divisor] fits.
 *
 * Split into the whole and the remainder of [value] over [divisor] first, so the only product
 * that is ever formed is a remainder (under [divisor]) times [multiplier].
 */
private fun multiplyDivideRounded(
    value: Long,
    multiplier: Long,
    divisor: Long,
): Long {
    val whole = value / divisor
    val remainder = value % divisor
    return whole * multiplier + (remainder * multiplier + divisor / 2) / divisor
}

/** The long unit a run is paced over and a ride is clocked in: the one every distance is typed in. */
private val UnitSystem.paceStretch: DistanceUnit
    get() = distanceEntryUnit

/** The short unit a swim's hundred is counted in. */
private val UnitSystem.swimStretch: DistanceUnit
    get() = when (this) {
        UnitSystem.METRIC -> DistanceUnit.METRE
        UnitSystem.IMPERIAL -> DistanceUnit.YARD
    }

private val UnitSystem.speedUnit: MeasureUnit
    get() = when (this) {
        UnitSystem.METRIC -> MeasureUnit.KILOMETER_PER_HOUR
        UnitSystem.IMPERIAL -> MeasureUnit.MILE_PER_HOUR
    }
