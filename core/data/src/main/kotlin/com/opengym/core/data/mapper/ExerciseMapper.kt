package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.domain.model.DifficultyLevel
import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import javax.inject.Inject

/**
 * [ExerciseEntity] to [Exercise] and back.
 *
 * A class with an injectable constructor rather than a file of extension functions
 * so the repositories declare the translation as a dependency, and unscoped per
 * `ARCHITECTURE.md` — it holds nothing, so a fresh instance per injection site
 * costs less than the scope would.
 */
class ExerciseMapper @Inject constructor() {

    fun toDomain(entity: ExerciseEntity): Exercise =
        Exercise(
            id = entity.id,
            name = entity.name,
            // Falls back to the same value the column defaults to, so a row carrying a
            // type this build does not know reads as an ordinary strength exercise
            // rather than dropping out of the catalogue.
            type = decodeEnum<ExerciseType>(entity.exerciseType) ?: ExerciseType.STRENGTH,
            discipline = decodeEnum<Discipline>(entity.discipline),
            // OTHER, not null, for an unrecognised value: EquipmentType declares it as
            // the catch-all, and null here would mean "no equipment recorded", which is
            // a different claim from "equipment we cannot name".
            equipmentType = entity.equipmentType?.let { decodeEnum<EquipmentType>(it) ?: EquipmentType.OTHER },
            machineBrand = entity.machineBrand,
            mechanicType = decodeEnum<MechanicType>(entity.mechanicType),
            difficultyLevel = decodeEnum<DifficultyLevel>(entity.difficultyLevel),
            isUnilateral = entity.isUnilateral,
            defaultRest = entity.defaultRest,
            notes = entity.notes,
            instructions = entity.instructions,
            isCustom = entity.isCustom,
            icon = entity.icon,
        )

    fun toEntity(exercise: Exercise): ExerciseEntity =
        ExerciseEntity(
            id = exercise.id,
            name = exercise.name,
            exerciseType = exercise.type.name,
            discipline = exercise.discipline?.name,
            equipmentType = exercise.equipmentType?.name,
            machineBrand = exercise.machineBrand,
            mechanicType = exercise.mechanicType?.name,
            difficultyLevel = exercise.difficultyLevel?.name,
            isUnilateral = exercise.isUnilateral,
            defaultRest = exercise.defaultRest,
            notes = exercise.notes,
            instructions = exercise.instructions,
            isCustom = exercise.isCustom,
            icon = exercise.icon,
        )
}
