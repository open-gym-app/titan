package com.opengym.core.domain.model

/**
 * One exercise of a saved template, in the order it is laid out.
 *
 * @property supersetGroup which superset this exercise belongs to within its template,
 *   or null when it stands alone. Copied onto the session when the template is applied,
 *   so a template's supersets are the session's supersets. See
 *   [com.opengym.core.domain.superset.Supersets].
 */
data class TemplateExercise(
    val id: Long = 0,
    val templateId: Long,
    val exerciseId: Long,
    val position: Int,
    override val supersetGroup: Int? = null,
    override val defaultSets: Int? = null,
    val defaultReps: Int? = null,
    val defaultWeight: Long? = null,
    override val restDuration: Long? = null,
    val notes: String? = null,
) : PlannedExercise
