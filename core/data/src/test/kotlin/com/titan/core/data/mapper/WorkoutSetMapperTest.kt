package com.titan.core.data.mapper

import com.titan.core.data.db.entity.WorkoutSetEntity
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutSetMapperTest {

    private val mapper = WorkoutSetMapper()

    private fun entity(
        rpe: Int? = null,
        setType: String = "NORMAL",
    ) = WorkoutSetEntity(
        id = 3,
        sessionExerciseId = 2,
        setNumber = 1,
        rpe = rpe,
        setType = setType,
        completedAt = 1_000,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    @Test
    fun `round trips every field`() {
        val set = WorkoutSet(
            id = 11,
            sessionExerciseId = 4,
            setNumber = 3,
            reps = 8,
            weight = 100_000_000,
            duration = 45,
            distance = 500,
            heartRateAvg = 130,
            heartRateMax = 160,
            elevationGain = 12_000,
            steps = 8_400,
            restAfter = 90,
            rpe = 85,
            setType = SetType.DROP_SET,
            completedAt = 1_700_000_000_000,
            isCompleted = true,
            notes = "last one moved slowly",
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_000_001,
        )

        assertEquals(set, mapper.toDomain(mapper.toEntity(set)))
    }

    @Test
    fun `keeps an RPE that is inside the range`() {
        assertEquals(WorkoutSet.RPE_MAX, mapper.toDomain(entity(rpe = WorkoutSet.RPE_MAX)).rpe)
        assertEquals(WorkoutSet.RPE_MIN, mapper.toDomain(entity(rpe = WorkoutSet.RPE_MIN)).rpe)
    }

    @Test
    fun `an out of range RPE reads as unrated instead of throwing`() {
        // SQLite cannot express the CHECK, so an import or an older build can leave
        // one behind; the set itself still has to come back.
        assertNull(mapper.toDomain(entity(rpe = WorkoutSet.RPE_MAX + 1)).rpe)
        assertNull(mapper.toDomain(entity(rpe = 0)).rpe)
    }

    @Test
    fun `an unknown set type falls back to the column default`() {
        assertEquals(SetType.NORMAL, mapper.toDomain(entity(setType = "SUPERSET")).setType)
    }

    @Test
    fun `writes the set type as its constant name`() {
        val set = WorkoutSet(
            sessionExerciseId = 1,
            setNumber = 1,
            setType = SetType.WARMUP,
            completedAt = 0,
            createdAt = 0,
            updatedAt = 0,
        )

        assertEquals("WARMUP", mapper.toEntity(set).setType)
    }
}
