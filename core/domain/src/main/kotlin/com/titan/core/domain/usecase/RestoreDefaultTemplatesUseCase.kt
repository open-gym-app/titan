package com.titan.core.domain.usecase

import com.titan.core.domain.model.DefaultTemplate
import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.repository.DefaultTemplateCatalogue
import com.titan.core.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Puts back the default templates the user has deleted, and leaves the rest alone.
 *
 * The defaults are ordinary templates once seeded — nothing marks a row as one — so "missing"
 * can only be decided by name: a default is missing when no template carries its name in the
 * device's current language, compared trimmed and case-insensitively, the same comparison
 * [CreateTemplateUseCase] refuses a duplicate by. That makes the rule simple to state and to
 * predict from the template list:
 *
 * - **An edited default keeps its edits.** Its name still matches, so it is not touched.
 * - **A renamed default counts as gone**, and its original comes back beside it. The user's
 *   rename is theirs; the original is what they asked for by restoring.
 * - **A case-only rename is not a rename.** "legs" is the "Legs" the user already has, and
 *   creating a second would be refused as a duplicate anyway.
 *
 * Each default goes through [CreateTemplateUseCase], the path every template takes, so a
 * definition that fails validation surfaces as its [TemplateError] rather than as a row the
 * editor would refuse to save.
 */
class RestoreDefaultTemplatesUseCase(
    private val catalogue: DefaultTemplateCatalogue,
    private val templateRepository: TemplateRepository,
    private val createTemplate: CreateTemplateUseCase,
) {

    /**
     * The defaults that restoring would add, in the order the app defines them, re-emitted
     * whenever a template is written. Empty when every default is present.
     */
    fun observeMissing(): Flow<List<DefaultTemplate>> =
        templateRepository.getAll().map { existing -> catalogue.getAll().missingFrom(existing) }

    /**
     * Creates every missing default, returning how many were added — zero when none were
     * missing.
     *
     * Stops at the first default that is refused. The ones created before it stay: each is a
     * complete template written in its own transaction, and a retry skips them by name.
     */
    suspend operator fun invoke(): Result<Int> {
        val missing = catalogue.getAll().missingFrom(templateRepository.getAll().first())
        for (default in missing) {
            createTemplate(
                name = default.name,
                exercises = default.exercises,
                category = default.category,
            ).onFailure { return Result.failure(it) }
        }
        return Result.success(missing.size)
    }

    // `equals(ignoreCase)` rather than a lowered set, so this is character for character the
    // comparison the duplicate check in `validateTemplate` makes: a default this calls missing
    // is one that [CreateTemplateUseCase] will not then refuse as taken.
    private fun List<DefaultTemplate>.missingFrom(existing: List<WorkoutTemplate>): List<DefaultTemplate> =
        filterNot { default ->
            val name = default.name.trim()
            existing.any { it.name.equals(name, ignoreCase = true) }
        }
}
