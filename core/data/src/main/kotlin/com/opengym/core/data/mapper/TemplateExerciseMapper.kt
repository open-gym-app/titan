package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.TemplateSetEntity
import com.opengym.core.data.db.projection.TemplateExerciseWithSets
import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import javax.inject.Inject

/**
 * [TemplateExerciseWithSets] to [TemplateExercise], and [TemplateExerciseDraft] to
 * the rows that store it.
 *
 * Drafts only travel inwards: a draft is what a caller supplies before the rows
 * exist, so there is no `toDomain` for one and no way back from a stored row to
 * something that has already forgotten its id.
 */
class TemplateExerciseMapper @Inject constructor() {

    /**
     * One exercise and its plan, with the sets put back in performance order.
     *
     * The sort is not decoration. `@Relation` promises the right child rows and nothing
     * about their order — see [TemplateExerciseWithSets] — and a plan whose sets came
     * back in insertion order by chance today would come back rearranged the first time
     * SQLite chose a different path, which is a warm-up drawn after the working sets.
     */
    fun toDomain(row: TemplateExerciseWithSets): TemplateExercise = TemplateExercise(
        id = row.exercise.id,
        templateId = row.exercise.templateId,
        exerciseId = row.exercise.exerciseId,
        position = row.exercise.position,
        supersetGroup = row.exercise.supersetGroup,
        sets = row.sets.sortedBy(TemplateSetEntity::setNumber).map(::toDomain),
        restDuration = row.exercise.restDuration,
        notes = row.exercise.notes,
    )

    /**
     * [drafts] as the rows of [templateId], numbered from position 0 in the order given,
     * each carrying its sets numbered from 1 in theirs.
     *
     * The numbering is here rather than at the call site because `template_exercise`
     * carries a unique `(template_id, position)` index and `template_set` a unique
     * `(template_exercise_id, set_number)` index, and a hand-numbered list is one reorder
     * away from violating either — the same reason [TemplateExerciseDraft] has no
     * position field and [PlannedSet] no number. Pass `templateId = 0` when the template
     * row does not exist yet; `WorkoutTemplateDao.insertWithExercises` stamps the real id
     * in, and does the same for each set's `template_exercise_id`, which cannot be known
     * here at all.
     *
     * `supersetGroup` travels as given. Its rules — at least two members, contiguous,
     * numbered densely — are the domain's
     * ([com.opengym.core.domain.superset.Supersets]), enforced on the way in by the
     * template use cases, and re-deriving them here would only give a caller a second,
     * quieter answer to the same question.
     */
    fun toEntities(templateId: Long, drafts: List<TemplateExerciseDraft>): List<TemplateExerciseWithSets> =
        drafts.mapIndexed { position, draft ->
            TemplateExerciseWithSets(
                exercise = TemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = draft.exerciseId,
                    position = position,
                    supersetGroup = draft.supersetGroup,
                    restDuration = draft.restDuration,
                    notes = draft.notes,
                ),
                sets = draft.sets.mapIndexed { index, set ->
                    TemplateSetEntity(
                        // Stamped by the DAO, which is the only place the id exists.
                        templateExerciseId = 0,
                        setNumber = index + 1,
                        setType = set.setType.name,
                        reps = set.reps,
                        weight = set.weight,
                    )
                },
            )
        }

    /**
     * One stored set as the plan it holds.
     *
     * A `set_type` this build does not know reads as [SetType.NORMAL] rather than making
     * the set — and with it the whole template — unreadable, matching how every other
     * enum-like column is decoded. See `TemplateMapper`.
     */
    private fun toDomain(entity: TemplateSetEntity): PlannedSet = PlannedSet(
        setType = decodeEnum<SetType>(entity.setType) ?: SetType.NORMAL,
        reps = entity.reps,
        weight = entity.weight,
    )
}
