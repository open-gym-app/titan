package com.titan.core.domain.importer

import com.titan.core.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceUnitsTest {

    @Test
    fun `a weight converts to milligrams, rounded to the nearest`() {
        assertEquals(100_000_000L, SourceWeightUnit.KILOGRAM.toMilligrams(100.0))
        // 45 lb is 20,411,656.65 mg.
        assertEquals(20_411_657L, SourceWeightUnit.POUND.toMilligrams(45.0))
        // What a float made of 75 kg is 75 kg.
        assertEquals(75_000_000L, SourceWeightUnit.KILOGRAM.toMilligrams(74.99999999999999))
    }

    @Test
    fun `a weight unit reads from the labels exports write`() {
        assertEquals(SourceWeightUnit.KILOGRAM, SourceWeightUnit.parse("KG"))
        assertEquals(SourceWeightUnit.KILOGRAM, SourceWeightUnit.parse("kgs"))
        assertEquals(SourceWeightUnit.POUND, SourceWeightUnit.parse(" lbs. "))
        assertEquals(SourceWeightUnit.POUND, SourceWeightUnit.parse("Pounds"))
        assertNull(SourceWeightUnit.parse("stone"))
        assertNull(SourceWeightUnit.parse(""))
    }

    @Test
    fun `a weight with its unit written after it reads as both`() {
        assertEquals(WeightFigure(64.5, SourceWeightUnit.KILOGRAM), WeightFigure.parse("64.5kg"))
        assertEquals(WeightFigure(95.5, SourceWeightUnit.KILOGRAM), WeightFigure.parse(" 95.5KG "))
        assertEquals(WeightFigure(142.0, SourceWeightUnit.POUND), WeightFigure.parse("142 lbs."))
        assertEquals(WeightFigure(80.5, null), WeightFigure.parse("80,5"))
        assertEquals(WeightFigure(-5.0, null), WeightFigure.parse("-5"))
        val spaces = " ".repeat(100_000)
        assertEquals(WeightFigure(64.5, SourceWeightUnit.KILOGRAM), WeightFigure.parse("64.5${spaces}kg"))
        assertNull(WeightFigure.parse("1${spaces}1kg"))
        listOf(null, "", "kg", "64.5 stone", "about 80kg", "64.5kg2").forEach { text ->
            assertNull(text, WeightFigure.parse(text))
        }
    }

    @Test
    fun `a weight figure is in its own unit, or the one given when it names none`() {
        assertEquals(64_500_000L, WeightFigure(64.5, SourceWeightUnit.KILOGRAM).milligrams(SourceWeightUnit.POUND))
        assertEquals(83_460_996L, WeightFigure(184.0, null).milligrams(SourceWeightUnit.POUND))
    }

    @Test
    fun `a bare weight is in the unit the user displays`() {
        assertEquals(SourceWeightUnit.KILOGRAM, SourceWeightUnit.of(UnitSystem.METRIC))
        assertEquals(SourceWeightUnit.POUND, SourceWeightUnit.of(UnitSystem.IMPERIAL))
    }

    @Test
    fun `a distance converts to centimetres, rounded to the nearest`() {
        assertEquals(200_000L, SourceDistanceUnit.METRE.toCentimetres(2_000.0))
        assertEquals(520_000L, SourceDistanceUnit.KILOMETRE.toCentimetres(5.2))
        // 3.1 mi is 498,896.64 cm.
        assertEquals(498_897L, SourceDistanceUnit.MILE.toCentimetres(3.1))
        // 10 ft is 304.8 cm.
        assertEquals(305L, SourceDistanceUnit.FOOT.toCentimetres(10.0))
        assertEquals(9_144L, SourceDistanceUnit.YARD.toCentimetres(100.0))
    }

    @Test
    fun `a distance unit reads from the labels exports write`() {
        assertEquals(SourceDistanceUnit.METRE, SourceDistanceUnit.parse("Meters"))
        assertEquals(SourceDistanceUnit.METRE, SourceDistanceUnit.parse("m"))
        assertEquals(SourceDistanceUnit.KILOMETRE, SourceDistanceUnit.parse("KM"))
        assertEquals(SourceDistanceUnit.MILE, SourceDistanceUnit.parse("mi."))
        assertEquals(SourceDistanceUnit.MILE, SourceDistanceUnit.parse("miles"))
        assertEquals(SourceDistanceUnit.FOOT, SourceDistanceUnit.parse("ft"))
        assertEquals(SourceDistanceUnit.YARD, SourceDistanceUnit.parse("yd"))
        assertEquals(SourceDistanceUnit.FOOT, SourceDistanceUnit.parse("Feet"))
        assertNull(SourceDistanceUnit.parse("furlongs"))
    }

    @Test
    fun `a bare distance is in the long distance the user displays`() {
        assertEquals(SourceDistanceUnit.KILOMETRE, SourceDistanceUnit.of(UnitSystem.METRIC))
        assertEquals(SourceDistanceUnit.MILE, SourceDistanceUnit.of(UnitSystem.IMPERIAL))
    }

    @Test
    fun `a circumference converts to millimetres, rounded to the nearest`() {
        assertEquals(812L, SourceLengthUnit.MILLIMETRE.toMillimetres(812.4))
        assertEquals(815L, SourceLengthUnit.CENTIMETRE.toMillimetres(81.5))
        // 32.5 in is 825.5 mm, which rounds up.
        assertEquals(826L, SourceLengthUnit.INCH.toMillimetres(32.5))
    }

    @Test
    fun `a length unit reads from the labels exports write, and never from a distance's`() {
        assertEquals(SourceLengthUnit.CENTIMETRE, SourceLengthUnit.parse("CM"))
        assertEquals(SourceLengthUnit.CENTIMETRE, SourceLengthUnit.parse("Centimetres"))
        assertEquals(SourceLengthUnit.INCH, SourceLengthUnit.parse(" in. "))
        assertEquals(SourceLengthUnit.INCH, SourceLengthUnit.parse("inches"))
        assertEquals(SourceLengthUnit.MILLIMETRE, SourceLengthUnit.parse("mm"))
        assertNull(SourceLengthUnit.parse("m"))
        assertNull(SourceLengthUnit.parse("km"))
        assertNull(SourceLengthUnit.parse(""))
    }

    @Test
    fun `a bare circumference is in the length the user measures a body in`() {
        assertEquals(SourceLengthUnit.CENTIMETRE, SourceLengthUnit.of(UnitSystem.METRIC))
        assertEquals(SourceLengthUnit.INCH, SourceLengthUnit.of(UnitSystem.IMPERIAL))
    }
}
