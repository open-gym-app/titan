package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.SessionExerciseEntity
import com.opengym.core.domain.model.SessionExercise
import javax.inject.Inject

/** [SessionExerciseEntity] to [SessionExercise] and back. */
class SessionExerciseMapper @Inject constructor() {

    fun toDomain(entity: SessionExerciseEntity): SessionExercise = SessionExercise(
        id = entity.id,
        sessionId = entity.sessionId,
        exerciseId = entity.exerciseId,
        position = entity.position,
        templateExerciseId = entity.templateExerciseId,
        notes = entity.notes,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    fun toEntity(sessionExercise: SessionExercise): SessionExerciseEntity = SessionExerciseEntity(
        id = sessionExercise.id,
        sessionId = sessionExercise.sessionId,
        exerciseId = sessionExercise.exerciseId,
        position = sessionExercise.position,
        templateExerciseId = sessionExercise.templateExerciseId,
        notes = sessionExercise.notes,
        createdAt = sessionExercise.createdAt,
        updatedAt = sessionExercise.updatedAt,
    )
}
