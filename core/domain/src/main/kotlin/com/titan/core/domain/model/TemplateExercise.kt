package com.titan.core.domain.model

/**
 * One exercise of a saved template, in the order it is laid out, with the sets it plans.
 *
 * @property sets what to perform, in order — see [PlannedSet]. Read back from the
 *   `template_set` rows this exercise owns, numbered from 1 in this order. Empty is a
 *   template line that names an exercise and leaves its sets to the day.
 * @property supersetGroup which superset this exercise belongs to within its template,
 *   or null when it stands alone. Copied onto the session when the template is applied,
 *   so a template's supersets are the session's supersets. See
 *   [com.titan.core.domain.superset.Supersets].
 */
data class TemplateExercise(
    val id: Long = 0,
    val templateId: Long,
    override val exerciseId: Long,
    val position: Int,
    override val supersetGroup: Int? = null,
    override val sets: List<PlannedSet> = emptyList(),
    val notes: String? = null,
) : PlannedExercise
