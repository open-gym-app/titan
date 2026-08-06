package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.WorkoutSessionEntity
import com.opengym.core.domain.model.WorkoutSession
import javax.inject.Inject

/** [WorkoutSessionEntity] to [WorkoutSession] and back. */
class SessionMapper @Inject constructor() {

    fun toDomain(entity: WorkoutSessionEntity): WorkoutSession = WorkoutSession(
        id = entity.id,
        templateId = entity.templateId,
        completedAt = entity.completedAt,
        isInProgress = entity.isInProgress,
        lastActiveAt = entity.lastActiveAt,
        notes = entity.notes,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    fun toEntity(session: WorkoutSession): WorkoutSessionEntity = WorkoutSessionEntity(
        id = session.id,
        templateId = session.templateId,
        completedAt = session.completedAt,
        isInProgress = session.isInProgress,
        lastActiveAt = session.lastActiveAt,
        notes = session.notes,
        createdAt = session.createdAt,
        updatedAt = session.updatedAt,
    )
}
