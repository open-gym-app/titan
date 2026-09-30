package com.titan.core.domain.repository

import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutTemplate
import kotlinx.coroutines.flow.Flow

/**
 * Storage for saved exercise sequences.
 *
 * A template and its exercises are one editable unit — a template with no exercises
 * plans nothing — so [insert] and [update] take both together and implementations
 * must apply them in a single transaction. Splitting them across two calls would let
 * a failure halfway leave a named template with an empty or half-written body, which
 * is exactly the state [com.titan.core.domain.usecase.CreateTemplateUseCase]
 * refuses to create.
 */
interface TemplateRepository {

    /** Every template, ordered by name. Re-emits on any template write. */
    fun getAll(): Flow<List<WorkoutTemplate>>

    fun getById(id: Long): Flow<WorkoutTemplate?>

    /** [templateId]'s exercises, ordered by position. Empty when the template is absent. */
    fun getExercises(templateId: Long): Flow<List<TemplateExercise>>

    /**
     * Every template's exercises in one read, ordered by template and then by position.
     *
     * For the screens that render *all* the templates at once and need something off
     * each one's body — the exercise count and the derived duration on a template row
     * ([com.titan.core.domain.model.TemplateSummary]). The alternative is one
     * [getExercises] subscription per template, which is a query per row and a set of
     * flows that has to be rebuilt every time a template is added or deleted.
     *
     * Callers group by [TemplateExercise.templateId]. The ordering within a template is
     * the same contract [getExercises] gives, so a group can be read as a plan without
     * being re-sorted.
     */
    fun getAllExercises(): Flow<List<TemplateExercise>>

    /**
     * Stores [template] and [exercises] together, returning the new template id.
     *
     * [exercises] is stored in list order, numbered from position 0 — see
     * [TemplateExerciseDraft]. `template.id` is ignored; the store assigns it.
     */
    suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long

    /**
     * Replaces `template.id`'s row *and* its whole exercise list, numbered from
     * position 0 as in [insert].
     *
     * Wholesale replacement rather than a diff: an edit screen hands back the list it
     * has, and reconciling that against the stored rows is a job the store can do in
     * one transaction and a use case cannot do atomically at all. The cost is that
     * `session_exercise.template_exercise_id` is `SET NULL`-ed for sessions that
     * referenced the replaced rows, which loses "this set came from that template
     * line" for past sessions; the sessions themselves are untouched.
     *
     * A no-op when `template.id` is absent, as in [delete]. Updating a template that
     * is not there is the same non-event as deleting one that is not there, and an
     * edit screen racing a delete from another screen is the ordinary way to reach
     * it — not a case worth its own error path.
     */
    suspend fun update(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    )

    /** Deletes the template and, by cascade, its exercises. A no-op when [id] is absent. */
    suspend fun delete(id: Long)
}
