package com.titan.core.domain.importer

import com.titan.core.domain.model.CENTIMETRES_PER_FOOT
import com.titan.core.domain.model.CENTIMETRES_PER_KILOMETRE
import com.titan.core.domain.model.CENTIMETRES_PER_METRE
import com.titan.core.domain.model.CENTIMETRES_PER_MILE
import com.titan.core.domain.model.CENTIMETRES_PER_YARD
import com.titan.core.domain.model.MILLIGRAMS_PER_KILOGRAM
import com.titan.core.domain.model.MILLIGRAMS_PER_POUND
import com.titan.core.domain.model.MILLIMETRES_PER_CENTIMETRE
import com.titan.core.domain.model.MILLIMETRES_PER_INCH
import com.titan.core.domain.model.UnitSystem
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The unit a source wrote a weight in, and the one conversion from it to Titan's milligrams.
 *
 * Every app says what its weights are in a different place — a column header (`Weight (kg)`), a
 * column of its own (`Weight Unit`), or nowhere at all, leaving the user's setting in the other
 * app as the only answer. A parser works out which of these it has; this is what it converts
 * with once it knows, so that every source rounds the same way and a pound is the same pound.
 */
enum class SourceWeightUnit(private val milligrams: Double) {
    KILOGRAM(MILLIGRAMS_PER_KILOGRAM),
    POUND(MILLIGRAMS_PER_POUND),
    ;

    /**
     * [value] in milligrams, rounded to the nearest one.
     *
     * Rounded rather than truncated because exports write what a float made of the user's
     * figure — `74.99999999999999` for 75 kg — and truncating that loses a milligram the user
     * never meant to lose, which is enough to make two equal lifts compare unequal.
     */
    fun toMilligrams(value: Double): Long = (value * milligrams).roundToLong()

    companion object {

        private val LABELS = mapOf(
            "kg" to KILOGRAM,
            "kgs" to KILOGRAM,
            "kilogram" to KILOGRAM,
            "kilograms" to KILOGRAM,
            "lb" to POUND,
            "lbs" to POUND,
            "pound" to POUND,
            "pounds" to POUND,
        )

        /**
         * The unit a figure with no unit beside it is read in: the one [unitSystem] displays.
         * See [ImportContext.unitSystem] for why that is the best evidence there is.
         */
        fun of(unitSystem: UnitSystem): SourceWeightUnit =
            when (unitSystem) {
                UnitSystem.METRIC -> KILOGRAM
                UnitSystem.IMPERIAL -> POUND
            }

        /**
         * The unit [label] names — `kg`, `lbs`, `Pounds` — or null when it names none. Case,
         * surrounding space and a trailing full stop (`lbs.`) are ignored.
         */
        fun parse(label: String): SourceWeightUnit? = LABELS[unitKey(label)]
    }
}

/**
 * The unit a source wrote a distance in, and the one conversion from it to Titan's centimetres.
 *
 * The weight's counterpart, for the same reasons; see [SourceWeightUnit].
 */
enum class SourceDistanceUnit(private val centimetres: Double) {
    METRE(CENTIMETRES_PER_METRE),
    KILOMETRE(CENTIMETRES_PER_KILOMETRE),

    /** The international foot, 0.3048 m exactly: FitNotes offers it for a distance. */
    FOOT(CENTIMETRES_PER_FOOT),
    YARD(CENTIMETRES_PER_YARD),

    /** The international mile, 1,609.344 m exactly. */
    MILE(CENTIMETRES_PER_MILE),
    ;

    /** [value] in centimetres, rounded to the nearest one. */
    fun toCentimetres(value: Double): Long = (value * centimetres).roundToLong()

    companion object {

        private val LABELS = mapOf(
            "m" to METRE,
            "meter" to METRE,
            "meters" to METRE,
            "metre" to METRE,
            "metres" to METRE,
            "km" to KILOMETRE,
            "kms" to KILOMETRE,
            "kilometer" to KILOMETRE,
            "kilometers" to KILOMETRE,
            "kilometre" to KILOMETRE,
            "kilometres" to KILOMETRE,
            "ft" to FOOT,
            "foot" to FOOT,
            "feet" to FOOT,
            "yd" to YARD,
            "yds" to YARD,
            "yard" to YARD,
            "yards" to YARD,
            "mi" to MILE,
            "mile" to MILE,
            "miles" to MILE,
        )

        /**
         * The unit a distance with no unit beside it is read in: the long distance [unitSystem]
         * displays, kilometres or miles, because that is what a tracker asks for a run in.
         */
        fun of(unitSystem: UnitSystem): SourceDistanceUnit =
            when (unitSystem) {
                UnitSystem.METRIC -> KILOMETRE
                UnitSystem.IMPERIAL -> MILE
            }

        /**
         * The unit [label] names — `m`, `km`, `ft`, `yd`, `Miles`, `mi.` — or null when it names none. Case,
         * surrounding space and a trailing full stop are ignored.
         */
        fun parse(label: String): SourceDistanceUnit? = LABELS[unitKey(label)]
    }
}

/**
 * The unit a source wrote a body circumference in, and the one conversion from it to Titan's
 * millimetres.
 *
 * Kept apart from [SourceDistanceUnit] because the two are read from different labels and
 * stored at different scales: an `in` beside a waist is an inch, and a tape measure is never
 * read in kilometres or miles, so no label is shared and no rounding is either.
 */
enum class SourceLengthUnit(private val millimetres: Double) {
    MILLIMETRE(1.0),
    CENTIMETRE(MILLIMETRES_PER_CENTIMETRE),

    /** The international inch, 25.4 mm exactly. */
    INCH(MILLIMETRES_PER_INCH),
    ;

    /** [value] in millimetres, rounded to the nearest one. */
    fun toMillimetres(value: Double): Long = (value * millimetres).roundToLong()

    companion object {

        private val LABELS = mapOf(
            "mm" to MILLIMETRE,
            "millimeter" to MILLIMETRE,
            "millimeters" to MILLIMETRE,
            "millimetre" to MILLIMETRE,
            "millimetres" to MILLIMETRE,
            "cm" to CENTIMETRE,
            "centimeter" to CENTIMETRE,
            "centimeters" to CENTIMETRE,
            "centimetre" to CENTIMETRE,
            "centimetres" to CENTIMETRE,
            "in" to INCH,
            "inch" to INCH,
            "inches" to INCH,
        )

        /**
         * The unit a circumference with no unit beside it is read in: the one [unitSystem] measures
         * a body in, centimetres or inches.
         */
        fun of(unitSystem: UnitSystem): SourceLengthUnit =
            when (unitSystem) {
                UnitSystem.METRIC -> CENTIMETRE
                UnitSystem.IMPERIAL -> INCH
            }

        /**
         * The unit [label] names — `cm`, `in`, `Inches` — or null when it names none. Case,
         * surrounding space and a trailing full stop (`in.`) are ignored.
         */
        fun parse(label: String): SourceLengthUnit? = LABELS[unitKey(label)]
    }
}

private fun unitKey(label: String): String = label.trim().removeSuffix(".").trim().lowercase(Locale.ROOT)
