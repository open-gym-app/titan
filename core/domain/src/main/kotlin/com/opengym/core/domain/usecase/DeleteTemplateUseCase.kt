package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.repository.TemplateRepository

/**
 * Removes a template and, by cascade, its exercises.
 *
 * Sessions started from it are not touched — `workout_session.template_id` is
 * `SET NULL`, so the history survives the plan it was run from.
 *
 * Deleting a template that is already gone fails with [TemplateError.NotFound]
 * rather than succeeding quietly: two screens racing on the same delete is worth
 * surfacing, and the caller is the only one that can tell "already done" from
 * "the id I am holding is stale".
 */
class DeleteTemplateUseCase(
    private val templateRepository: TemplateRepository,
) {

    suspend operator fun invoke(templateId: Long): Result<Unit> {
        templateRepository.loadTemplate(templateId)
            .getOrElse { return Result.failure(it) }

        templateRepository.delete(templateId)
        return Result.success(Unit)
    }
}
