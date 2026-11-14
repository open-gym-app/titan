package com.titan.core.domain.validation

import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GymValidatorTest {

    private val valid = Gym(
        name = "Iron Works",
        address = "12 Forge Street",
        coordinates = Coordinates(51.5074, -0.1278),
        equipmentTags = listOf("squat rack", "dumbbells to 50 kg"),
        createdAt = 100,
    )

    private fun reason(gym: Gym): String = (GymValidator.validate(gym) as ValidationResult.Invalid).reason

    @Test
    fun `a complete gym is valid`() {
        assertEquals(ValidationResult.Valid, GymValidator.validate(valid))
    }

    @Test
    fun `equipment tags are optional`() {
        assertEquals(ValidationResult.Valid, GymValidator.validate(valid.copy(equipmentTags = emptyList())))
    }

    @Test
    fun `a blank name is refused`() {
        assertTrue(reason(valid.copy(name = "  ")).contains("name"))
    }

    @Test
    fun `a blank address is refused`() {
        assertTrue(reason(valid.copy(address = "")).contains("address"))
    }

    /** The pin is optional: a name and an address are a gym. */
    @Test
    fun `a gym with no coordinates is valid`() {
        assertEquals(ValidationResult.Valid, GymValidator.validate(valid.copy(coordinates = null)))
    }

    @Test
    fun `the coordinate ranges are inclusive`() {
        listOf(Coordinates(90.0, 180.0), Coordinates(-90.0, -180.0)).forEach {
            assertEquals(ValidationResult.Valid, GymValidator.validate(valid.copy(coordinates = it)))
        }
    }

    @Test
    fun `a latitude or longitude out of range is refused`() {
        assertTrue(reason(valid.copy(coordinates = Coordinates(90.1, 0.0))).contains("latitude"))
        assertTrue(reason(valid.copy(coordinates = Coordinates(0.0, -180.1))).contains("longitude"))
    }

    @Test
    fun `NaN and infinite coordinates are refused`() {
        assertTrue(reason(valid.copy(coordinates = Coordinates(Double.NaN, 0.0))).contains("latitude"))
        assertTrue(reason(valid.copy(coordinates = Coordinates(0.0, Double.POSITIVE_INFINITY))).contains("longitude"))
    }

    @Test
    fun `a blank equipment tag is refused`() {
        assertTrue(reason(valid.copy(equipmentTags = listOf("rack", " "))).contains("blank"))
    }

    @Test
    fun `an equipment tag with a line break is refused`() {
        assertTrue(reason(valid.copy(equipmentTags = listOf("squat\nrack"))).contains("single line"))
        assertTrue(reason(valid.copy(equipmentTags = listOf("squat\rrack"))).contains("single line"))
    }

    @Test
    fun `every violation is reported at once`() {
        val reason = reason(valid.copy(name = "", coordinates = Coordinates(200.0, 0.0)))

        assertTrue(reason, reason.contains("name") && reason.contains("latitude"))
    }
}
