package com.titan.core.ui.format

import android.icu.util.MeasureUnit
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
) {
    METRE(centimetres = 100.0, measureUnit = MeasureUnit.METER, fractionDigits = 0),
    KILOMETRE(centimetres = 100_000.0, measureUnit = MeasureUnit.KILOMETER, fractionDigits = 2),
    YARD(centimetres = 91.44, measureUnit = MeasureUnit.YARD, fractionDigits = 0),
    MILE(centimetres = 160_934.4, measureUnit = MeasureUnit.MILE, fractionDigits = 2),
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
 * The unit a distance is *typed* in on this system: kilometres or miles, or metres or yards for
 * an exercise measured in pool lengths (#M7-17).
 *
 * One unit per field rather than [distanceUnitFor]'s per-value choice, because a field has to
 * say which unit it is in before anything has been typed — its label, its parse and the way a
 * stored distance is put back into it all have to be the same choice, or `5` is five kilometres
 * to the lifter and five metres to the parser. The two ends of the system's range are the two
 * answers: a run is typed in the long unit, and a swim in the short one, because `0.4` for a
 * 400 m swim is a figure nobody types.
 *
 * @param shortDistances whether the exercise is measured in pool lengths — see [isSwim].
 */
fun UnitSystem.distanceEntryUnit(shortDistances: Boolean): DistanceUnit =
    when (this) {
        UnitSystem.METRIC -> if (shortDistances) DistanceUnit.METRE else DistanceUnit.KILOMETRE
        UnitSystem.IMPERIAL -> if (shortDistances) DistanceUnit.YARD else DistanceUnit.MILE
    }

/** The unit [exercise]'s distances are typed in on this system. Null types in the long unit. */
fun UnitSystem.distanceEntryUnit(exercise: Exercise?): DistanceUnit = distanceEntryUnit(exercise?.isSwim == true)

/**
 * Whether this exercise is swum, and so measured in pool lengths rather than kilometres.
 *
 * **Read off the name**, because nothing else says so. The catalogue has no column for it — a
 * swim is `ENDURANCE` and `CARDIO` like a run — and adding one for the sake of a field's unit
 * would be a schema change to answer a display question. The seed names every swim the way
 * upstream names its variants (`Swimming, Freestyle`, `Swimming, Kick Set`) and the pool's one
 * other movement `Aqua Jogging`, so the two stems are the whole of it. A user's own exercise
 * named in another language falls back to the long unit, which costs a decimal point and not a
 * wrong figure: the parse and the display are the same unit either way.
 */
val Exercise.isSwim: Boolean get() = SWIM_STEMS.any { name.contains(it, ignoreCase = true) }

/** The name fragments [isSwim] recognises. */
private val SWIM_STEMS = listOf("swim", "aqua")

/**
 * The units one exercise's set fields are typed and read in (#M7-17): the distance unit its rows
 * are labelled with, and what a bare number in its time field counts.
 *
 * One value for both, resolved in one place, because every screen that labels a field and every
 * one that parses it has to give the same answer — a field labelled in metres and parsed in
 * kilometres is a set logged a thousand times too long. See [distanceEntryUnit] and
 * [bareClockNumber].
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
                distanceUnit = unitSystem.distanceEntryUnit(exercise),
                bareClockNumber = exercise?.type.bareClockNumber,
            )
    }
}
