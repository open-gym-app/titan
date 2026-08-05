package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.first

/**
 * The rules [CreateTemplateUseCase] and [UpdateTemplateUseCase] share, kept in one
 * place so the two cannot drift: a template that could not be created must not be
 * reachable by editing an existing one into the same shape.
 */

/**
 * Checks name and body, returning the trimmed name to store.
 *
 * Trimming is not cosmetic: `" Push day "` and `"Push day"` are the same template to
 * a user, and storing the untrimmed form would defeat the duplicate check for the
 * next caller. A name that is only whitespace trims to empty and is refused.
 *
 * [selfId] is excluded from the duplicate scan so re-saving a template under its own
 * name is not a collision; pass 0 when there is no template yet.
 */
internal suspend fun TemplateRepository.validateTemplate(
    name: String,
    exercises: List<TemplateExerciseDraft>,
    selfId: Long = 0,
): Result<String> {
    val trimmed = name.trim()
    // Ordered, and short-circuiting: the name check is free, the duplicate scan is a
    // repository read, so a blank name never pays for one.
    val failure = when {
        trimmed.isEmpty() -> TemplateError.EmptyName
        exercises.isEmpty() -> TemplateError.NoExercises
        nameTaken(trimmed, selfId) -> TemplateError.DuplicateName(trimmed)
        else -> null
    }
    return failure?.let { Result.failure(it) } ?: Result.success(trimmed)
}

/**
 * Whether a template other than [selfId] already carries [name].
 *
 * Case-insensitive by the same reasoning as the trim: "push day" and "Push Day" are
 * one template to the user, so offering both is offering a mistake.
 *
 * This is a check-then-act over a Flow read, so it is not a durable constraint — two
 * writes racing can both pass it. Nothing in the schema holds the invariant either:
 * `workout_template` has no unique index on `name`. The airtight version is
 * `CREATE UNIQUE INDEX ... ON workout_template(name COLLATE NOCASE)`, which belongs
 * with the persistence contract in M3-07; until then this is the only enforcement,
 * and it holds for the one-user-one-screen case it exists for.
 */
private suspend fun TemplateRepository.nameTaken(name: String, selfId: Long): Boolean =
    getAll().first().any { it.id != selfId && it.name.equals(name, ignoreCase = true) }

/**
 * Reads one template row, failing with [TemplateError.NotFound] instead of returning
 * null, so the use cases can unwrap it with `getOrElse`.
 */
internal suspend fun TemplateRepository.loadTemplate(templateId: Long): Result<WorkoutTemplate> =
    getById(templateId).first()
        ?.let { Result.success(it) }
        ?: Result.failure(TemplateError.NotFound(templateId))
