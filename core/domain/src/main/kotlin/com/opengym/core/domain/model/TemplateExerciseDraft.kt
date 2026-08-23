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
 * [sets] follows the same rule one level down: a planned set's number is its index
 * here, and `template_set` carries the matching unique `(template_exercise_id,
 * set_number)` index. See [PlannedSet].
 *
 * [supersetGroup] is the one place that ordering matters twice over: a group's members
 * are contiguous *in this list*, since the list is what the positions are read from.
 * The write path normalises the grouping before storing it — see
 * [com.opengym.core.domain.superset.Supersets] — so a caller may hand over a group left
 * with a single member after a removal without repairing it first.
 */
data class TemplateExerciseDraft(
    val exerciseId: Long,
    /** What to perform, in order. Empty is a line that plans no sets — see [PlannedSet]. */
    override val sets: List<PlannedSet> = emptyList(),
    /** Seconds, per [TemplateExercise.restDuration]. */
    override val restDuration: Long? = null,
    val notes: String? = null,
    override val supersetGroup: Int? = null,
) : PlannedExercise
