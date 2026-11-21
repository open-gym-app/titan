package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The two things [BodyMeasurement] derives rather than stores: lean mass, and whether the
 * entry records anything at all.
 */
class BodyMeasurementTest {

    private fun measurement(
        weight: Long? = null,
        neck: Int? = null,
        shoulders: Int? = null,
        chest: Int? = null,
        waist: Int? = null,
        hips: Int? = null,
        arm: Int? = null,
        forearm: Int? = null,
        thigh: Int? = null,
        calf: Int? = null,
        bodyFat: Int? = null,
        notes: String? = null,
    ) = BodyMeasurement(
        weight = weight,
        neck = neck,
        shoulders = shoulders,
        chest = chest,
        waist = waist,
        hips = hips,
        arm = arm,
        forearm = forearm,
        thigh = thigh,
        calf = calf,
        bodyFat = bodyFat,
        notes = notes,
        createdAt = 100,
        updatedAt = 100,
    )

    // --- lean mass --------------------------------------------------------

    @Test
    fun `lean mass is what is left of the weight after the fat`() {
        // 80 kg at 15.0% body fat is 68 kg of lean mass.
        assertEquals(68_000_000L, measurement(weight = 80_000_000, bodyFat = 150).leanMass)
    }

    @Test
    fun `a body fat of tenths is read as tenths, not as whole percent`() {
        // 15.5%, not 155% — the unit the column is documented in, and the digit a
        // scale-by-ten mistake would move by 9 kg.
        assertEquals(67_600_000L, measurement(weight = 80_000_000, bodyFat = 155).leanMass)
    }

    @Test
    fun `lean mass needs both figures`() {
        assertNull(measurement(weight = 80_000_000).leanMass)
        assertNull(measurement(bodyFat = 150).leanMass)
        assertNull(measurement(waist = 820).leanMass)
    }

    @Test
    fun `an all-fat body has no lean mass and a fat-free one is all lean`() {
        assertEquals(0L, measurement(weight = 80_000_000, bodyFat = 1_000).leanMass)
        assertEquals(80_000_000L, measurement(weight = 80_000_000, bodyFat = 0).leanMass)
    }

    @Test
    fun `lean mass truncates rather than rounding, and by at most a milligram`() {
        // 1 mg at 50.1% leaves 0.499 mg, which has nowhere to go in an integer field.
        assertEquals(0L, measurement(weight = 1, bodyFat = 501).leanMass)
    }

    // --- isEmpty ----------------------------------------------------------

    @Test
    fun `any single measurement makes an entry non-empty`() {
        // One per field: `isEmpty` is an eleven-way conjunction, and the way it goes wrong is a
        // field left out of it — which only a case per field detects.
        assertFalse(measurement(weight = 80_000_000).isEmpty)
        assertFalse(measurement(chest = 1_040).isEmpty)
        assertFalse(measurement(waist = 820).isEmpty)
        assertFalse(measurement(arm = 385).isEmpty)
        assertFalse(measurement(thigh = 610).isEmpty)
        assertFalse(measurement(bodyFat = 155).isEmpty)
        assertFalse(measurement(neck = 390).isEmpty)
        assertFalse(measurement(shoulders = 1_210).isEmpty)
        assertFalse(measurement(hips = 980).isEmpty)
        assertFalse(measurement(forearm = 300).isEmpty)
        assertFalse(measurement(calf = 390).isEmpty)
    }

    @Test
    fun `an entry carrying only a note records no measurement`() {
        assertTrue(measurement(notes = "felt lighter today").isEmpty)
    }

    @Test
    fun `an entry with nothing in it is empty`() {
        assertTrue(measurement().isEmpty)
    }

    /** `weight × (1000 − fat)` wrapped for a weight past `Long.MAX_VALUE / 1000`. */
    @Test
    fun `lean mass of a weight too heavy to multiply is still exact`() {
        val weight = Long.MAX_VALUE - 7
        val exact = BigInteger.valueOf(weight).multiply(BigInteger.valueOf(850)).divide(BigInteger.valueOf(1_000))

        assertEquals(exact.toLong(), measurement(weight = weight, bodyFat = 150).leanMass)
        assertEquals(weight, measurement(weight = weight, bodyFat = 0).leanMass)
    }

    @Test
    fun `a body fat outside the whole reads as no lean mass`() {
        assertNull(measurement(weight = 80_000_000, bodyFat = 1_001).leanMass)
        assertNull(measurement(weight = 80_000_000, bodyFat = -1).leanMass)
    }
}
