package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.TemplateRepository

/**
 * Saves a new exercise sequence under [name].
 *
 * Refused with [TemplateError.EmptyName] when the name is blank, with
 * [TemplateError.NoExercises] when the body is empty, and with
 * [TemplateError.DuplicateName] when another template already carries the name
 * case-insensitively. Nothing is written unless all three pass.
 *
 * [exercises] is stored in the order given, numbered from position 0.
 */
class CreateTemplateUseCase(
    private val templateRepository: TemplateRepository,
) {

    suspend operator fun invoke(
        name: String,
        exercises: List<TemplateExerciseDraft>,
        icon: String? = null,
    ): Result<WorkoutTemplate> {
        val validName = templateRepository.validateTemplate(name, exercises)
            .getOrElse { return Result.failure(it) }

        val template = WorkoutTemplate(name = validName, icon = icon)
        val id = templateRepository.insert(template, exercises)
        return Result.success(template.copy(id = id))
    }
}
