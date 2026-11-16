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
    }

    @Test
    fun `a distance unit reads from the labels exports write`() {
        assertEquals(SourceDistanceUnit.METRE, SourceDistanceUnit.parse("Meters"))
        assertEquals(SourceDistanceUnit.METRE, SourceDistanceUnit.parse("m"))
        assertEquals(SourceDistanceUnit.KILOMETRE, SourceDistanceUnit.parse("KM"))
        assertEquals(SourceDistanceUnit.MILE, SourceDistanceUnit.parse("mi."))
        assertEquals(SourceDistanceUnit.MILE, SourceDistanceUnit.parse("miles"))
        assertNull(SourceDistanceUnit.parse("yd"))
    }

    @Test
    fun `a bare distance is in the long distance the user displays`() {
        assertEquals(SourceDistanceUnit.KILOMETRE, SourceDistanceUnit.of(UnitSystem.METRIC))
        assertEquals(SourceDistanceUnit.MILE, SourceDistanceUnit.of(UnitSystem.IMPERIAL))
    }
}
