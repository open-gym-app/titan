package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DataFootprint]'s two claims: a footprint of all zeroes reads as empty, and a count cannot
 * be negative.
 *
 * Both matter because of where the value is rendered. The delete-all dialog's wording turns
 * on [DataFootprint.isEmpty], and a footprint that mis-reported an empty database would tell
 * a user with 200 sessions that there is nothing to lose.
 */
class DataFootprintTest {

    @Test
    fun `a footprint with no arguments is the empty one`() {
        assertEquals(DataFootprint(0, 0, 0, 0, 0, 0), DataFootprint())
        assertTrue(DataFootprint().isEmpty)
    }

    @Test
    fun `any single non-zero count makes it non-empty`() {
        // One per field: `isEmpty` is a six-way conjunction, and the way it goes wrong is a
        // field left out of it — which only a case per field detects.
        assertFalse(DataFootprint(sessions = 1).isEmpty)
        assertFalse(DataFootprint(sets = 1).isEmpty)
        assertFalse(DataFootprint(templates = 1).isEmpty)
        assertFalse(DataFootprint(personalRecords = 1).isEmpty)
        assertFalse(DataFootprint(customExercises = 1).isEmpty)
        assertFalse(DataFootprint(bodyMeasurements = 1).isEmpty)
    }

    @Test
    fun `a negative count is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DataFootprint(sessions = -1) }
        assertThrows(IllegalArgumentException::class.java) { DataFootprint(sets = -1) }
        assertThrows(IllegalArgumentException::class.java) { DataFootprint(templates = -1) }
        assertThrows(IllegalArgumentException::class.java) { DataFootprint(personalRecords = -1) }
        assertThrows(IllegalArgumentException::class.java) { DataFootprint(customExercises = -1) }
        assertThrows(IllegalArgumentException::class.java) { DataFootprint(bodyMeasurements = -1) }
    }

    @Test
    fun `photos are something to delete but not something a restore puts at stake`() {
        val photosOnly = DataFootprint(progressPhotos = 3)

        assertTrue(photosOnly.isEmpty)
        assertFalse(photosOnly.nothingToDelete)
        assertTrue(DataFootprint().nothingToDelete)
    }
}
