package com.titan.core.data.mapper

import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.domain.model.WorkoutActivity
import com.titan.core.domain.model.WorkoutSession
import javax.inject.Inject

/**
 * [WorkoutSessionEntity] to [WorkoutSession] and back.
 *
 * An activity name this build does not know reads back as null, as every enum column does (see
 * [decodeEnum]). For an imported session that only loses the label the duplicate check compares:
 * the row is still imported, because [WorkoutSession.healthConnectId] is what says so.
 */
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
            healthConnectId = entity.healthConnectId,
            healthConnectActivity = decodeEnum<WorkoutActivity>(entity.healthConnectActivity),
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
            healthConnectId = session.healthConnectId,
            healthConnectActivity = session.healthConnectActivity?.name,
        )
}
