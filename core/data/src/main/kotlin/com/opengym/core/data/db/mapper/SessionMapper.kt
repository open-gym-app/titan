package com.opengym.core.data.db.mapper

import com.opengym.core.data.db.entity.SessionEntity
import com.opengym.core.domain.model.WorkoutSession

internal fun SessionEntity.toDomain(): WorkoutSession = WorkoutSession(
    id = id,
    startedAt = startedAt,
    finishedAt = finishedAt,
    notes = notes,
)

internal fun WorkoutSession.toEntity(): SessionEntity = SessionEntity(
    id = id,
    startedAt = startedAt,
    finishedAt = finishedAt,
    notes = notes,
)
