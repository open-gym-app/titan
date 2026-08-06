package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.domain.model.DifficultyLevel
import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM tests, no Robolectric and no database: the mappers are pure functions
 * over data classes, and the point of extracting them is that the translation can be
 * pinned down without standing a Room instance up to produce a row.
 */
class ExerciseMapperTest {

    private val mapper = ExerciseMapper()

    @Test
    fun `round trips every field`() {
        val exercise = Exercise(
            id = 7,
            name = "Front Squat",
            type = ExerciseType.STRENGTH,
            discipline = Discipline.OLYMPIC_WEIGHTLIFTING,
            equipmentType = EquipmentType.BARBELL,
            machineBrand = "Eleiko",
            mechanicType = MechanicType.COMPOUND,
            difficultyLevel = DifficultyLevel.EXPERT,
            isUnilateral = false,
            defaultRest = 180,
            notes = "rack is set two holes low",
            instructions = "Rack the bar across the front delts.",
            isCustom = true,
            icon = "squat",
        )

        assertEquals(exercise, mapper.toDomain(mapper.toEntity(exercise)))
    }

    @Test
    fun `writes enums as their constant names`() {
        val entity = mapper.toEntity(
            Exercise(
                name = "Row",
                type = ExerciseType.ENDURANCE,
                discipline = Discipline.CARDIO,
                equipmentType = EquipmentType.MACHINE,
                mechanicType = MechanicType.COMPOUND,
                difficultyLevel = DifficultyLevel.BEGINNER,
            ),
        )

        assertEquals("ENDURANCE", entity.exerciseType)
        assertEquals("CARDIO", entity.discipline)
        assertEquals("MACHINE", entity.equipmentType)
        assertEquals("COMPOUND", entity.mechanicType)
        assertEquals("BEGINNER", entity.difficultyLevel)
    }

    @Test
    fun `keeps nullable enum columns null`() {
        val exercise = mapper.toDomain(ExerciseEntity(name = "Improvised"))

        assertNull(exercise.discipline)
        assertNull(exercise.equipmentType)
        assertNull(exercise.mechanicType)
        assertNull(exercise.difficultyLevel)
    }

    @Test
    fun `an unknown exercise type falls back to the column default`() {
        val exercise = mapper.toDomain(ExerciseEntity(name = "Imported", exerciseType = "PLYOMETRIC_HOLD"))

        assertEquals(ExerciseType.STRENGTH, exercise.type)
    }

    @Test
    fun `an unknown equipment type falls back to OTHER rather than to null`() {
        val exercise = mapper.toDomain(ExerciseEntity(name = "Imported", equipmentType = "SLED"))

        assertEquals(EquipmentType.OTHER, exercise.equipmentType)
    }

    @Test
    fun `an unknown value in a nullable enum column reads as absent`() {
        val exercise = mapper.toDomain(
            ExerciseEntity(name = "Imported", discipline = "CALISTHENICS", difficultyLevel = "GODLIKE"),
        )

        assertNull(exercise.discipline)
        assertNull(exercise.difficultyLevel)
    }
}
