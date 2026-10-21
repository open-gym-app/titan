package com.titan.core.domain.usecase

import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.repository.TemplateRepository

/**
 * Saves a new exercise sequence under [name].
 *
 * Refused with [TemplateError.EmptyName] when the name is blank, with
 * [TemplateError.NoExercises] when the body is empty, with
 * [TemplateError.InvalidSuperset] when a superset is split by an exercise that is not
 * in it, and with [TemplateError.DuplicateName] when another template already carries
 * the name case-insensitively. Nothing is written unless all four pass.
 *
 * [exercises] is stored in the order given, numbered from position 0, with its
 * superset grouping normalised — see [validateTemplate].
 *
 * [category] is stored as given and is not checked against anything: the categories are
 * a fixed set ([TemplateCategory]) and a template is legitimately saved without one,
 * which is what a "Save as template" that never asked leaves behind. There is no
 * duration to store alongside it — see [com.titan.core.domain.template.TemplateDuration].
 */
class CreateTemplateUseCase(private val templateRepository: TemplateRepository) {

    suspend operator fun invoke(
        name: String,
        exercises: List<TemplateExerciseDraft>,
        category: TemplateCategory? = null,
        icon: String? = null,
    ): Result<WorkoutTemplate> {
        val valid = templateRepository.validateTemplate(name, exercises)
            .getOrElse { return Result.failure(it) }

        val template = WorkoutTemplate(name = valid.name, category = category, icon = icon)
        val id = templateRepository.insert(template, valid.exercises)
        return Result.success(template.copy(id = id))
    }
}
