package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import javax.inject.Inject

/**
 * [TemplateExerciseEntity] to [TemplateExercise], and [TemplateExerciseDraft] to
 * the rows that store it.
 *
 * Drafts only travel inwards: a draft is what a caller supplies before the rows
 * exist, so there is no `toDomain` for one and no way back from a stored row to
 * something that has already forgotten its id.
 */
class TemplateExerciseMapper @Inject constructor() {

    fun toDomain(entity: TemplateExerciseEntity): TemplateExercise = TemplateExercise(
        id = entity.id,
        templateId = entity.templateId,
        exerciseId = entity.exerciseId,
        position = entity.position,
        supersetGroup = entity.supersetGroup,
        defaultSets = entity.defaultSets,
        defaultReps = entity.defaultReps,
        defaultWeight = entity.defaultWeight,
        restDuration = entity.restDuration,
        notes = entity.notes,
    )

    /**
     * [drafts] as rows of [templateId], numbered from position 0 in the order given.
     *
     * The numbering is here rather than at the call site because `template_exercise`
     * carries a unique `(template_id, position)` index and a hand-numbered list is
     * one reorder away from violating it — the same reason [TemplateExerciseDraft]
     * has no position field. Pass `templateId = 0` when the template row does not
     * exist yet; `WorkoutTemplateDao.insertWithExercises` stamps the real id in.
     *
     * `supersetGroup` travels as given. Its rules — at least two members, contiguous,
     * numbered densely — are the domain's
     * ([com.opengym.core.domain.superset.Supersets]), enforced on the way in by the
     * template use cases, and re-deriving them here would only give a caller a second,
     * quieter answer to the same question.
     */
    fun toEntities(templateId: Long, drafts: List<TemplateExerciseDraft>): List<TemplateExerciseEntity> =
        drafts.mapIndexed { position, draft ->
            TemplateExerciseEntity(
                templateId = templateId,
                exerciseId = draft.exerciseId,
                position = position,
                supersetGroup = draft.supersetGroup,
                defaultSets = draft.defaultSets,
                defaultReps = draft.defaultReps,
                defaultWeight = draft.defaultWeight,
                restDuration = draft.restDuration,
                notes = draft.notes,
            )
        }
}
