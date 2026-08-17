package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.TemplateRepository

/**
 * Rewrites an existing template: its name, its icon, and its whole exercise list.
 *
 * Enforces the same rules as [CreateTemplateUseCase] — a template must not be
 * editable into a shape it could not have been created in — plus
 * [TemplateError.NotFound] when [templateId] is absent, which is what a caller
 * holding a template deleted on another screen sees.
 *
 * Keeping the template's own name is not a [TemplateError.DuplicateName]: the
 * duplicate scan skips [templateId] itself.
 *
 * The exercise list replaces the stored one wholesale; see
 * [TemplateRepository.update] for what that costs.
 */
class UpdateTemplateUseCase(
    private val templateRepository: TemplateRepository,
) {

    suspend operator fun invoke(
        templateId: Long,
        name: String,
        exercises: List<TemplateExerciseDraft>,
        icon: String? = null,
    ): Result<WorkoutTemplate> {
        templateRepository.loadTemplate(templateId)
            .getOrElse { return Result.failure(it) }

        val valid = templateRepository.validateTemplate(name, exercises, selfId = templateId)
            .getOrElse { return Result.failure(it) }

        val updated = WorkoutTemplate(id = templateId, name = valid.name, icon = icon)
        templateRepository.update(updated, valid.exercises)
        return Result.success(updated)
    }
}
