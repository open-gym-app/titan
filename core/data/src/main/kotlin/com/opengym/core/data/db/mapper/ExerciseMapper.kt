package com.opengym.core.data.db.mapper

import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType

internal fun ExerciseEntity.toDomain(): Exercise = Exercise(
    id = id,
    name = name,
    type = ExerciseType.valueOf(exerciseType),
    primaryMuscleGroup = primaryMuscleGroup,
)

internal fun Exercise.toEntity(): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    exerciseType = type.name,
    primaryMuscleGroup = primaryMuscleGroup,
)
