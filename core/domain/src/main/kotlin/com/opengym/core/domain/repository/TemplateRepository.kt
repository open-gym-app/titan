package com.opengym.core.domain.repository

import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import kotlinx.coroutines.flow.Flow

/**
 * Storage for saved exercise sequences.
 *
 * A template and its exercises are one editable unit — a template with no exercises
 * plans nothing — so [insert] and [update] take both together and implementations
 * must apply them in a single transaction. Splitting them across two calls would let
 * a failure halfway leave a named template with an empty or half-written body, which
 * is exactly the state [com.opengym.core.domain.usecase.CreateTemplateUseCase]
 * refuses to create.
 */
interface TemplateRepository {

    /** Every template, ordered by name. Re-emits on any template write. */
    fun getAll(): Flow<List<WorkoutTemplate>>

    fun getById(id: Long): Flow<WorkoutTemplate?>

    /** [templateId]'s exercises, ordered by position. Empty when the template is absent. */
    fun getExercises(templateId: Long): Flow<List<TemplateExercise>>

    /**
     * Stores [template] and [exercises] together, returning the new template id.
     *
     * [exercises] is stored in list order, numbered from position 0 — see
     * [TemplateExerciseDraft]. `template.id` is ignored; the store assigns it.
     */
    suspend fun insert(template: WorkoutTemplate, exercises: List<TemplateExerciseDraft>): Long

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
     */
    suspend fun update(template: WorkoutTemplate, exercises: List<TemplateExerciseDraft>)

    /** Deletes the template and, by cascade, its exercises. A no-op when [id] is absent. */
    suspend fun delete(id: Long)
}
