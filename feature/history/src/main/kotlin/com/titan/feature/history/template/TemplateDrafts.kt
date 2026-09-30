package com.titan.feature.history.template

import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.TemplateExerciseDraft

/**
 * A stored template's exercises as the drafts a write takes.
 *
 * `CreateTemplateUseCase` and `UpdateTemplateUseCase` both take
 * [TemplateExerciseDraft]s — a template and its body are written together, wholesale — so every
 * write that starts from a template that already exists has to make this trip: renaming one,
 * duplicating one, and saving one out of the editor.
 *
 * **The list must be in position order**, which is the order [TemplateExercise]s are read back
 * in: a draft's position *is* its index in the list, and the superset grouping is read the same
 * way. Sorting is the caller's, so a caller holding a list it built rather than read cannot have
 * it silently reordered underneath it.
 *
 * `id` and `templateId` are dropped rather than carried, and that is the one thing worth knowing:
 * the rows are replaced, not updated, so the drafts have no identity to keep. See
 * [com.titan.core.domain.repository.TemplateRepository.update] for what that costs.
 */
internal fun List<TemplateExercise>.toDrafts(): List<TemplateExerciseDraft> =
    map { exercise ->
        TemplateExerciseDraft(
            exerciseId = exercise.exerciseId,
            // The plan carries over whole. A duplicate that kept the exercises and dropped what they
            // planned would be a copy of the template's table of contents.
            sets = exercise.sets,
            restDuration = exercise.restDuration,
            notes = exercise.notes,
            supersetGroup = exercise.supersetGroup,
        )
    }
