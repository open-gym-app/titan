package com.opengym.core.domain.validation

import com.opengym.core.domain.model.BodyMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementValidatorTest {

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

    private fun validate(measurement: BodyMeasurement) = BodyMeasurementValidator.validate(measurement)

    @Test
    fun `a full entry inside every bound is valid`() {
        val result = validate(
            measurement(
                weight = 79_800_000,
                chest = 1_040,
                waist = 820,
                arm = 385,
                thigh = 610,
                bodyFat = 155,
                notes = "morning, fasted",
            ),
        )

        assertTrue(result.isValid)
    }

    @Test
    fun `each measurement is valid on its own`() {
        // The optionality guarantee as a rule rather than as a nullable column: none of these
        // may be refused for the five figures it does not carry.
        assertTrue(validate(measurement(weight = 80_000_000)).isValid)
        assertTrue(validate(measurement(chest = 1_040)).isValid)
        assertTrue(validate(measurement(waist = 820)).isValid)
        assertTrue(validate(measurement(arm = 385)).isValid)
        assertTrue(validate(measurement(thigh = 610)).isValid)
        assertTrue(validate(measurement(bodyFat = 155)).isValid)
    }

    @Test
    fun `an entry recording nothing is refused`() {
        val result = validate(measurement())

        assertFalse(result.isValid)
        assertTrue(result.reasonOrNull().orEmpty().contains("at least one measurement"))
    }

    @Test
    fun `a note is not a measurement`() {
        assertFalse(validate(measurement(notes = "felt lighter today")).isValid)
    }

    @Test
    fun `a weight outside the bounds is refused, and zero is not a weight`() {
        assertFalse(validate(measurement(weight = 0)).isValid)
        assertFalse(validate(measurement(weight = -1)).isValid)
        assertFalse(validate(measurement(weight = BodyMeasurementValidator.WEIGHT_MAX_MG + 1)).isValid)

        assertTrue(validate(measurement(weight = BodyMeasurementValidator.WEIGHT_MIN_MG)).isValid)
        assertTrue(validate(measurement(weight = BodyMeasurementValidator.WEIGHT_MAX_MG)).isValid)
    }

    @Test
    fun `a circumference outside the bounds is refused, on every field that has one`() {
        val tooBig = BodyMeasurementValidator.CIRCUMFERENCE_MAX_MM + 1

        assertFalse(validate(measurement(chest = tooBig)).isValid)
        assertFalse(validate(measurement(waist = tooBig)).isValid)
        assertFalse(validate(measurement(arm = tooBig)).isValid)
        assertFalse(validate(measurement(thigh = tooBig)).isValid)
        assertFalse(validate(measurement(waist = 0)).isValid)

        assertTrue(validate(measurement(waist = BodyMeasurementValidator.CIRCUMFERENCE_MIN_MM)).isValid)
        assertTrue(validate(measurement(waist = BodyMeasurementValidator.CIRCUMFERENCE_MAX_MM)).isValid)
    }

    @Test
    fun `a body fat outside nothing-to-everything is refused`() {
        val zero = validate(measurement(bodyFat = 0))
        assertFalse(zero.isValid)
        // The refusal has to name a bound the refused value is outside of: rendering the floor
        // by integer division would say "0%..100%" to a user who just entered 0.0%.
        assertTrue(zero.reasonOrNull().orEmpty(), zero.reasonOrNull().orEmpty().contains("0.1%..100.0%"))
        assertFalse(validate(measurement(bodyFat = BodyMeasurementValidator.BODY_FAT_MAX + 1)).isValid)

        assertTrue(validate(measurement(bodyFat = BodyMeasurementValidator.BODY_FAT_MIN)).isValid)
        assertTrue(validate(measurement(bodyFat = BodyMeasurementValidator.BODY_FAT_MAX)).isValid)
    }

    @Test
    fun `every violation is reported at once`() {
        // A user who mis-typed two figures should see both, rather than fix one and be
        // refused again — the same contract SetValidator keeps.
        val result = validate(measurement(weight = -1, waist = 0))

        val reason = result.reasonOrNull().orEmpty()
        assertTrue(reason, reason.contains("weight"))
        assertTrue(reason, reason.contains("waist"))
        assertEquals(2, reason.split(";").size)
    }
}
