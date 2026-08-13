package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.SessionExerciseEntity
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import javax.inject.Inject

/**
 * [SessionExerciseEntity] to [SessionExercise] and back, and [SessionExerciseDraft] to
 * the rows that store it.
 *
 * Drafts only travel inwards, as `TemplateExerciseMapper`'s do: a draft is what a
 * caller supplies before the rows exist, so there is no `toDomain` for one.
 */
class SessionExerciseMapper @Inject constructor() {

    fun toDomain(entity: SessionExerciseEntity): SessionExercise = SessionExercise(
        id = entity.id,
        sessionId = entity.sessionId,
        exerciseId = entity.exerciseId,
        position = entity.position,
        supersetGroup = entity.supersetGroup,
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
        supersetGroup = sessionExercise.supersetGroup,
        templateExerciseId = sessionExercise.templateExerciseId,
        notes = sessionExercise.notes,
        createdAt = sessionExercise.createdAt,
        updatedAt = sessionExercise.updatedAt,
    )

    /**
     * [drafts] as rows of [sessionId], numbered from position 0 in the order given and
     * stamped with [createdAt].
     *
     * The numbering is here rather than at the call site for the reason
     * [SessionExerciseDraft] has no position field: `session_exercise` carries a unique
     * `(session_id, position)` index, and a hand-numbered list is one reorder away from
     * violating it.
     *
     * `supersetGroup` travels as given. Its rules are the domain's
     * ([com.opengym.core.domain.superset.Supersets]), applied on the way in by
     * `StartPlannedSessionUseCase`; re-deriving them here would give a caller a second,
     * quieter answer to the same question.
     */
    fun toEntities(
        sessionId: Long,
        drafts: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<SessionExerciseEntity> = drafts.mapIndexed { position, draft ->
        SessionExerciseEntity(
            sessionId = sessionId,
            exerciseId = draft.exerciseId,
            position = position,
            supersetGroup = draft.supersetGroup,
            templateExerciseId = draft.templateExerciseId,
            notes = draft.notes,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }
}
