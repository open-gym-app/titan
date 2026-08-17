package com.opengym.core.domain.model

/**
 * One exercise of a template as the caller supplies it, before it is stored.
 *
 * Distinct from [TemplateExercise] by what it deliberately leaves out: `id` and
 * `templateId` do not exist yet when a template is being created, and `position` is
 * taken from the order of the list rather than from a field. That last part is the
 * point — `template_exercise` carries a unique `(template_id, position)` index, and
 * a caller that hand-numbers positions is one reorder away from violating it. A
 * list is already ordered; the write path numbers it.
 *
 * [supersetGroup] is the one place that ordering matters twice over: a group's members
 * are contiguous *in this list*, since the list is what the positions are read from.
 * The write path normalises the grouping before storing it — see
 * [com.opengym.core.domain.superset.Supersets] — so a caller may hand over a group left
 * with a single member after a removal without repairing it first.
 */
data class TemplateExerciseDraft(
    val exerciseId: Long,
    val defaultSets: Int? = null,
    val defaultReps: Int? = null,
    /** Milligrams, per [TemplateExercise.defaultWeight]. */
    val defaultWeight: Long? = null,
    /** Seconds, per [TemplateExercise.restDuration]. */
    val restDuration: Long? = null,
    val notes: String? = null,
    // Appended rather than slotted next to the other structural field: it is an `Int?`
    // like `defaultSets`, so inserting it earlier would silently re-read any positional
    // `Draft(id, 4, 8)` as a group id instead of failing to compile.
    val supersetGroup: Int? = null,
)
