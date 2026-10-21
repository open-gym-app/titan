package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things [BodyMeasurement] derives rather than stores: lean mass, and whether the
 * entry records anything at all.
 */
class BodyMeasurementTest {

    private fun measurement(
        weight: Long? = null,
        chest: Int? = null,
        waist: Int? = null,
        arm: Int? = null,
        thigh: Int? = null,
        bodyFat: Int? = null,
        notes: String? = null,
    ) = BodyMeasurement(
        weight = weight,
        chest = chest,
        waist = waist,
        arm = arm,
        thigh = thigh,
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
        // One per field: `isEmpty` is a six-way conjunction, and the way it goes wrong is a
        // field left out of it — which only a case per field detects.
        assertFalse(measurement(weight = 80_000_000).isEmpty)
        assertFalse(measurement(chest = 1_040).isEmpty)
        assertFalse(measurement(waist = 820).isEmpty)
        assertFalse(measurement(arm = 385).isEmpty)
        assertFalse(measurement(thigh = 610).isEmpty)
        assertFalse(measurement(bodyFat = 155).isEmpty)
    }

    @Test
    fun `an entry carrying only a note records no measurement`() {
        assertTrue(measurement(notes = "felt lighter today").isEmpty)
    }

    @Test
    fun `an entry with nothing in it is empty`() {
        assertTrue(measurement().isEmpty)
    }
}
