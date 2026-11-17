package com.titan.core.ui.format

import android.icu.util.MeasureUnit
import com.titan.core.domain.model.CENTIMETRES_PER_KILOMETRE
import com.titan.core.domain.model.CENTIMETRES_PER_METRE
import com.titan.core.domain.model.CENTIMETRES_PER_MILE
import com.titan.core.domain.model.CENTIMETRES_PER_YARD
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.UnitSystem

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

    /**
     * How many tenths of a millimetre make one of this unit — the same length as [centimetres],
     * as an integer.
     *
     * The tenth of a millimetre is the coarsest length every unit here is a whole number of: a
     * yard is 9,144 of them and a mile 16,093,440. For arithmetic that must not round on the way
     * — a pace per mile is worked out from whole seconds and whole centimetres, and converted
     * through this rather than through a `Double`.
     */
    internal val decimillimetres: Long,

    /** The CLDR unit this is, which is where its name and its number-and-unit pattern come from. */
    internal val measureUnit: MeasureUnit,

    /**
     * How many decimals a distance in this unit is shown to.
     *
     * None for the short units, because a metre is already finer than any treadmill or GPS
     * reports and `437.2 yd` claims a precision nobody has. Two for the long ones, so a
     * 5.25 km run does not collapse to 5 km.
     */
    internal val fractionDigits: Int,

    /**
     * How many decimals a distance *typed* in this unit keeps — about a metre or a yard, in
     * every unit.
     *
     * Finer than [fractionDigits] for the long units, because every distance is typed in one of
     * those (see [distanceEntryUnit]) and a 25 m pool length is `0.025 km`: at a readout's two
     * decimals it would be stored as 30 m.
     */
    internal val entryFractionDigits: Int,
) {
    METRE(
        centimetres = CENTIMETRES_PER_METRE,
        decimillimetres = 10_000,
        measureUnit = MeasureUnit.METER,
        fractionDigits = 0,
        entryFractionDigits = 0,
    ),
    KILOMETRE(
        centimetres = CENTIMETRES_PER_KILOMETRE,
        decimillimetres = 10_000_000,
        measureUnit = MeasureUnit.KILOMETER,
        fractionDigits = 2,
        entryFractionDigits = 3,
    ),
    YARD(
        centimetres = CENTIMETRES_PER_YARD,
        decimillimetres = 9_144,
        measureUnit = MeasureUnit.YARD,
        fractionDigits = 0,
        entryFractionDigits = 0,
    ),
    MILE(
        centimetres = CENTIMETRES_PER_MILE,
        decimillimetres = 16_093_440,
        measureUnit = MeasureUnit.MILE,
        fractionDigits = 2,
        entryFractionDigits = 3,
    ),
    ;

    /**
     * What the unit is called next to a number: `m`, `km`, `yd`, `mi` — and `jardas` or
     * `milhas` where a locale spells them out.
     *
     * A property rather than a constructor argument because the answer is a function of the
     * formatting locale, which an enum constant is initialised long before anything knows.
     * The counterpart of [com.titan.core.ui.format.weightSuffix]; see `MeasureFormats.kt`
     * for why a unit symbol is never a literal.
     */
    val suffix: String get() = unitSymbol(measureUnit)
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
fun UnitSystem.distanceUnitFor(centimetres: Long): DistanceUnit =
    when (this) {
        UnitSystem.METRIC ->
            if (centimetres.readsUnder(DistanceUnit.KILOMETRE.centimetres, DistanceUnit.METRE)) {
                DistanceUnit.METRE
            } else {
                DistanceUnit.KILOMETRE
            }

        UnitSystem.IMPERIAL ->
            if (centimetres.readsUnder(DistanceUnit.MILE.centimetres * MILE_THRESHOLD, DistanceUnit.YARD)) {
                DistanceUnit.YARD
            } else {
                DistanceUnit.MILE
            }
    }

/**
 * Whether [this] many centimetres are still under [threshold] *as [unit] would show them*.
 *
 * The rounding has to happen before the comparison, not after, or the two disagree at the
 * boundary: 999.9 m is under a kilometre but displays as `1000` at the metre's zero decimals,
 * and `1000 m` is the app spelling out a kilometre in the unit it just decided not to use.
 * Rounding first sends that value to `1 km`, which is what it reads as anyway.
 */
private fun Long.readsUnder(
    threshold: Double,
    unit: DistanceUnit,
): Boolean = (this / unit.centimetres).roundToFractionDigits(unit.fractionDigits) * unit.centimetres < threshold

/** The fraction of a mile under which a distance reads better in yards. */
private const val MILE_THRESHOLD = 0.5

/**
 * The unit every distance is *typed* in on this system: kilometres or miles (#M7-17).
 *
 * One unit per system rather than [distanceUnitFor]'s per-value choice, because a field has to
 * say which unit it is in before anything has been typed — its label, its parse and the way a
 * stored distance is put back into it all have to be the same choice, or `5` is five kilometres
 * to the lifter and five metres to the parser.
 *
 * And one unit for every exercise, a swim or a row as much as a run. A per-exercise unit has to
 * be decided from something, and nothing on an exercise says how far its sets go — a name
 * heuristic gets a custom or translated exercise wrong silently, a thousandfold. The long unit
 * typed to [DistanceUnit.entryFractionDigits] takes a pool length as exactly as a marathon.
 */
val UnitSystem.distanceEntryUnit: DistanceUnit
    get() = when (this) {
        UnitSystem.METRIC -> DistanceUnit.KILOMETRE
        UnitSystem.IMPERIAL -> DistanceUnit.MILE
    }

/**
 * The units one exercise's set fields are parsed in (#M7-17): the system's distance unit, and
 * what a bare number in its time field counts for the exercise's mode.
 *
 * One value for both, resolved in one place, so every parse and every re-format of a field
 * asks the same question the same way. See [distanceEntryUnit] and [bareClockNumber].
 */
data class EntryUnits(
    val distanceUnit: DistanceUnit,
    val bareClockNumber: BareClockNumber,
) {
    companion object {
        /** The units [exercise]'s fields take on [unitSystem]. A null exercise takes the fallbacks. */
        fun of(
            exercise: Exercise?,
            unitSystem: UnitSystem,
        ): EntryUnits =
            EntryUnits(
                distanceUnit = unitSystem.distanceEntryUnit,
                bareClockNumber = exercise?.type.bareClockNumber,
            )
    }
}
