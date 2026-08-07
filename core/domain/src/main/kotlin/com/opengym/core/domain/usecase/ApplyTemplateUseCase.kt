package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplatePlan
import com.opengym.core.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.first

/**
 * Reads out the plan a template holds — its name, and its exercises ordered by
 * position.
 *
 * **Starts nothing.** No session is created or modified, and no template row is
 * touched — this is a read. A caller wires the result into
 * [StartSessionUseCase] and the session-exercise writes itself, which is what keeps
 * "the user picked a template" and "the user began training" two separate,
 * separately cancellable decisions. The name travels with the plan for the same
 * reason the exercises do: it is what the session is started under
 * (`startSession(templateId = plan.templateId, name = plan.name)`), and reading it
 * here saves the caller a second load of a template it has just read.
 *
 * Each returned [TemplateExercise] carries the catalogue `exerciseId` plus the
 * template's defaults — sets, reps, weight, rest, notes — so the session can be
 * pre-filled without a second read. It intentionally does not resolve those ids into
 * [com.opengym.core.domain.model.Exercise] rows: that is
 * [com.opengym.core.domain.repository.ExerciseRepository]'s job, the caller usually
 * has the catalogue loaded already, and pulling it in here would make every
 * template-apply pay for a join it may not need.
 *
 * Fails with [TemplateError.NotFound] when the template is absent, and with
 * [TemplateError.NoExercises] when it stores none — which the write path refuses to
 * create, so seeing it means the rows were changed underneath (an import, a manual
 * edit) and starting an empty session from it would be worse than saying so.
 */
class ApplyTemplateUseCase(
    private val templateRepository: TemplateRepository,
) {

    suspend operator fun invoke(templateId: Long): Result<TemplatePlan> {
        val template = templateRepository.loadTemplate(templateId)
            .getOrElse { return Result.failure(it) }

        // Re-sorted rather than trusted: the repository contract orders by position,
        // but the ordering is what a caller maps onto session positions, so it is
        // worth one sort to not depend on an implementation honouring it.
        val exercises = templateRepository.getExercises(templateId).first()
            .sortedBy(TemplateExercise::position)

        return if (exercises.isEmpty()) {
            Result.failure(TemplateError.NoExercises)
        } else {
            Result.success(
                TemplatePlan(templateId = templateId, name = template.name, exercises = exercises),
            )
        }
    }
}
