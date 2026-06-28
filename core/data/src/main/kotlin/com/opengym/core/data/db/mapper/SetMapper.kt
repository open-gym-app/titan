package com.opengym.core.data.db.mapper

import com.opengym.core.data.db.entity.SetEntity
import com.opengym.core.domain.model.WorkoutSet

internal fun SetEntity.toDomain(): WorkoutSet = WorkoutSet(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    setOrder = setOrder,
    reps = reps,
    weightKg = weightKg,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    notes = notes,
)

internal fun WorkoutSet.toEntity(): SetEntity = SetEntity(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    setOrder = setOrder,
    reps = reps,
    weightKg = weightKg,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    notes = notes,
)
