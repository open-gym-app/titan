package com.opengym.core.ui.format

import com.opengym.core.domain.model.UnitSystem

/**
 * A unit a distance can be shown in, and how many centimetres of it there are.
 *
 * A named unit rather than a boolean over km/mi, because a distance field has to *say*
 * which unit it is in — the label on the input, the suffix on the readout and the divisor
 * used to parse it back all have to be the same choice, and an enum is how they stay so.
 *
 * Every factor is exact by definition: a yard is 0.9144 m and a mile 1760 of them.
 */
enum class DistanceUnit(
    /** How many centimetres make one of this unit. */
    val centimetres: Double,

    /** What the unit is called next to a number. */
    val suffix: String,

    /**
     * How many decimals a distance in this unit is shown to.
     *
     * None for the short units, because a metre is already finer than any treadmill or GPS
     * reports and `437.2 yd` claims a precision nobody has. Two for the long ones, so a
     * 5.25 km run does not collapse to 5 km.
     */
    internal val fractionDigits: Int,
) {
    METRE(centimetres = 100.0, suffix = "m", fractionDigits = 0),
    KILOMETRE(centimetres = 100_000.0, suffix = "km", fractionDigits = 2),
    YARD(centimetres = 91.44, suffix = "yd", fractionDigits = 0),
    MILE(centimetres = 160_934.4, suffix = "mi", fractionDigits = 2),
}

/**
 * The unit [centimetres] reads best in, for a user on this system.
 *
 * The scope of #M4-17 is "centimetres to km or mi", and for a run that is right — but a
 * gym logs 400 m intervals, a 100 m sprint and a 25 m pool length too, and `0.1 km` is a
 * worse answer than `100 m` for every one of them. So each system gets a short unit and a
 * long one, and the threshold sits where the short one starts being unwieldy: a kilometre,
 * and half a mile.
 *
 * The imperial short unit is the yard rather than the foot because that is what a track is
 * marked in; the metric one is the metre for the same reason.
 */
fun UnitSystem.distanceUnitFor(centimetres: Long): DistanceUnit = when (this) {
    UnitSystem.METRIC ->
        if (centimetres < DistanceUnit.KILOMETRE.centimetres) DistanceUnit.METRE else DistanceUnit.KILOMETRE

    UnitSystem.IMPERIAL ->
        if (centimetres < DistanceUnit.MILE.centimetres * MILE_THRESHOLD) DistanceUnit.YARD else DistanceUnit.MILE
}

/** The fraction of a mile under which a distance reads better in yards. */
private const val MILE_THRESHOLD = 0.5
