package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.TemplateRepository

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
 */
class CreateTemplateUseCase(
    private val templateRepository: TemplateRepository,
) {

    suspend operator fun invoke(
        name: String,
        exercises: List<TemplateExerciseDraft>,
        icon: String? = null,
    ): Result<WorkoutTemplate> {
        val valid = templateRepository.validateTemplate(name, exercises)
            .getOrElse { return Result.failure(it) }

        val template = WorkoutTemplate(name = valid.name, icon = icon)
        val id = templateRepository.insert(template, valid.exercises)
        return Result.success(template.copy(id = id))
    }
}
