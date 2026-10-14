package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoordinatesTest {

    @Test
    fun `the extremes are valid`() {
        Coordinates(90.0, 180.0)
        Coordinates(-90.0, -180.0)
    }

    @Test
    fun `latitude beyond a pole is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Coordinates(90.01, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Coordinates(-90.01, 0.0) }
    }

    @Test
    fun `longitude beyond the antimeridian is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Coordinates(0.0, 180.01) }
        assertThrows(IllegalArgumentException::class.java) { Coordinates(0.0, -180.01) }
    }

    @Test
    fun `NaN is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Coordinates(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Coordinates(0.0, Double.NaN) }
    }

    @Test
    fun `a gym defaults to no tags, no notes and an unassigned id`() {
        val gym = Gym(name = "A", address = "B", coordinates = Coordinates(1.0, 2.0), createdAt = 5L)
        assertEquals(0L, gym.id)
        assertEquals(emptyList<String>(), gym.equipmentTags)
        assertEquals(null, gym.userNotes)
    }
}
