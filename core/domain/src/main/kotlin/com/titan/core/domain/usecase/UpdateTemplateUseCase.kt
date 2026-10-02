package com.titan.core.domain.usecase

import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.repository.TemplateRepository

/**
 * Rewrites an existing template: its name, its category, its icon, and its whole
 * exercise list.
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
 *
 * **Every field is a rewrite, not a patch.** [category] and [icon] are stored as given,
 * so omitting one clears what was there — an edit screen hands back the whole template
 * it is showing, and a use case that merged the omitted fields with the stored row
 * would leave no way to remove a category at all. The estimated duration is not among
 * the fields because it is not a field: it follows from the new exercise list without a
 * write ([com.titan.core.domain.template.TemplateDuration]).
 */
class UpdateTemplateUseCase(private val templateRepository: TemplateRepository) {

    suspend operator fun invoke(
        templateId: Long,
        name: String,
        exercises: List<TemplateExerciseDraft>,
        category: TemplateCategory? = null,
        icon: String? = null,
    ): Result<WorkoutTemplate> {
        templateRepository.loadTemplate(templateId)
            .getOrElse { return Result.failure(it) }

        val valid = templateRepository.validateTemplate(name, exercises, selfId = templateId)
            .getOrElse { return Result.failure(it) }

        val updated = WorkoutTemplate(
            id = templateId,
            name = valid.name,
            category = category,
            icon = icon,
        )
        templateRepository.update(updated, valid.exercises)
        return Result.success(updated)
    }
}
