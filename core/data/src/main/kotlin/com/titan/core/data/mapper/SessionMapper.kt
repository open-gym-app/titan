package com.titan.core.data.mapper

import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.domain.model.WorkoutSession
import javax.inject.Inject

/** [WorkoutSessionEntity] to [WorkoutSession] and back. */
class SessionMapper @Inject constructor() {

    fun toDomain(entity: WorkoutSessionEntity): WorkoutSession =
        WorkoutSession(
            id = entity.id,
            templateId = entity.templateId,
            name = entity.name,
            completedAt = entity.completedAt,
            isInProgress = entity.isInProgress,
            lastActiveAt = entity.lastActiveAt,
            notes = entity.notes,
            calories = entity.calories,
            rating = entity.rating,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    fun toEntity(session: WorkoutSession): WorkoutSessionEntity =
        WorkoutSessionEntity(
            id = session.id,
            templateId = session.templateId,
            name = session.name,
            completedAt = session.completedAt,
            isInProgress = session.isInProgress,
            lastActiveAt = session.lastActiveAt,
            notes = session.notes,
            calories = session.calories,
            rating = session.rating,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
        )
}
