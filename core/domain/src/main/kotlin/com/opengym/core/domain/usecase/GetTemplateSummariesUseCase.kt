package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.template.TemplateDuration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The saved templates as a list renders them: name, category, exercise count and the
 * estimated duration, re-emitted whenever any of the three inputs changes.
 *
 * [GetTemplatesUseCase] answers "what templates are there"; this answers "what does a
 * template row say", which is a different question with two more sources behind it.
 * Both exist because the second is not always wanted — an editor picking a template to
 * open needs the names and none of the arithmetic.
 *
 * ### Why the duration is computed here and not in the UI
 *
 * The estimate is a domain rule ([TemplateDuration]) that needs the user's configured
 * rest, and both screens that render it — the new-session picker (#25) and the
 * templates tab (#24) — would otherwise have to collect
 * [UserPreferencesRepository] for the one field and remember to pass it. A screen that
 * forgot would show every user the shipped default's estimate, which is right on most
 * phones and quietly wrong on the ones that matter.
 *
 * ### Why the exercises are read whole rather than per template
 *
 * One [TemplateRepository.getAllExercises] subscription, grouped here, rather than a
 * [TemplateRepository.getExercises] flow per template. The per-template shape needs a
 * `flatMapLatest` that rebuilds the whole set of subscriptions every time a template is
 * created or deleted, for a table that holds a few dozen rows.
 *
 * Nothing is stored: editing a template changes what this emits with no extra write,
 * which is the point of deriving the estimate rather than persisting it.
 */
class GetTemplateSummariesUseCase(
    private val templateRepository: TemplateRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    operator fun invoke(): Flow<List<TemplateSummary>> = combine(
        templateRepository.getAll(),
        templateRepository.getAllExercises(),
        userPreferencesRepository.observe(),
    ) { templates, exercises, preferences ->
        val byTemplate = exercises.groupBy(TemplateExercise::templateId)

        templates.map { template ->
            // Re-sorted rather than trusted, as `ApplyTemplateUseCase` does and for the
            // same reason: position order is what both the estimate and the superset
            // grouping are read in, and it is worth one sort over a handful of rows to
            // not depend on an implementation honouring a contract.
            val body = byTemplate[template.id].orEmpty().sortedBy(TemplateExercise::position)

            TemplateSummary(
                template = template,
                exerciseCount = body.size,
                estimatedDuration = TemplateDuration.estimate(body, preferences.defaultRestDuration),
            )
        }
    }
}
