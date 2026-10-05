package com.titan.core.domain.usecase

import com.titan.core.domain.model.DefaultTemplateName
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
 * can only be decided by name: a default is missing when no template carries its name in any
 * language the app ships, compared trimmed and case-insensitively, the same comparison
 * [CreateTemplateUseCase] refuses a duplicate by. That makes the rule simple to state and to
 * predict from the template list:
 *
 * - **An edited default keeps its edits.** Its name still matches, so it is not touched.
 * - **A renamed default counts as gone**, and its original comes back beside it. The user's
 *   rename is theirs; the original is what they asked for by restoring.
 * - **A case-only rename is not a rename.** "legs" is the "Legs" the user already has, and
 *   creating a second would be refused as a duplicate anyway.
 * - **A change of language is not a deletion.** "Legs", seeded on an English phone, is still
 *   the Legs default on a French one. Matching only the current language's "Jambes" would call
 *   every default deleted and restore a French copy beside each English one.
 *
 * A default that is missing comes back under its name in the current language.
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
     * The names of the defaults that restoring would add, in the order the app defines them,
     * re-emitted whenever a template is written. Empty when every default is present.
     *
     * Names only, from [DefaultTemplateCatalogue.getNames]: the list is decided again on every
     * write, and a restore of several defaults is several writes, so resolving every default's
     * exercises against the database each time would be work the answer never uses.
     */
    fun observeMissing(): Flow<List<DefaultTemplateName>> =
        templateRepository.getAll().map { existing ->
            catalogue.getNames().filterNot { it.isTakenIn(existing) }
        }

    /**
     * Creates every missing default, returning how many were added — zero when none were
     * missing.
     *
     * Stops at the first default that is refused. The ones created before it stay: each is a
     * complete template written in its own transaction, and a retry skips them by name.
     *
     * Fails, after creating the rest, when a missing default cannot be resolved against this
     * database's catalogue. [DefaultTemplateCatalogue.getAll] leaves such a default out, but
     * [observeMissing] lists it — it reads names only — so reporting success would tell the
     * user every default is back while the row still offers the one that is not. An import is
     * what makes this reachable: it replaces the catalogue with the file's, which need not hold
     * every exercise this build's defaults name.
     */
    suspend operator fun invoke(): Result<Int> {
        val existing = templateRepository.getAll().first()
        val missing = catalogue.getAll().filterNot { it.name.isTakenIn(existing) }
        for (default in missing) {
            createTemplate(
                name = default.name.current,
                exercises = default.exercises,
                category = default.category,
            ).onFailure { return Result.failure(it) }
        }
        val unresolved = catalogue.getNames().count { !it.isTakenIn(existing) } - missing.size
        if (unresolved > 0) {
            val reason = "$unresolved missing defaults name exercises not in the catalogue"
            return Result.failure(IllegalStateException(reason))
        }
        return Result.success(missing.size)
    }

    // `equals(ignoreCase)` rather than a lowered set, so this is character for character the
    // comparison the duplicate check in `validateTemplate` makes: a default this calls missing
    // is one that [CreateTemplateUseCase] will not then refuse as taken.
    private fun DefaultTemplateName.isTakenIn(existing: List<WorkoutTemplate>): Boolean =
        everyLanguage.any { candidate ->
            val name = candidate.trim()
            existing.any { it.name.equals(name, ignoreCase = true) }
        }
}
