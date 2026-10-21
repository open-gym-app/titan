package com.titan.core.data.mapper

import com.titan.core.domain.model.BodyMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyMeasurementMapperTest {

    private val mapper = BodyMeasurementMapper()

    @Test
    fun `round trips every field`() {
        val measurement = BodyMeasurement(
            id = 7,
            weight = 79_800_000,
            chest = 1_040,
            waist = 820,
            arm = 385,
            thigh = 610,
            bodyFat = 155,
            notes = "morning, fasted",
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_000_001,
        )

        assertEquals(measurement, mapper.toDomain(mapper.toEntity(measurement)))
    }

    @Test
    fun `round trips an entry that records weight alone`() {
        // The optionality guarantee, through the mapper: five nulls stay five nulls rather
        // than becoming the zeroes a non-null column would have forced.
        val measurement = BodyMeasurement(weight = 80_000_000, createdAt = 100, updatedAt = 100)

        val roundTripped = mapper.toDomain(mapper.toEntity(measurement))

        assertEquals(measurement, roundTripped)
        assertNull(roundTripped.chest)
        assertNull(roundTripped.waist)
        assertNull(roundTripped.arm)
        assertNull(roundTripped.thigh)
        assertNull(roundTripped.bodyFat)
    }

    @Test
    fun `round trips an entry with no weight in it`() {
        val measurement = BodyMeasurement(waist = 820, createdAt = 100, updatedAt = 100)

        val roundTripped = mapper.toDomain(mapper.toEntity(measurement))

        assertEquals(measurement, roundTripped)
        assertNull(roundTripped.weight)
    }
}
