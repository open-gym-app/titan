package com.titan.core.domain.model

/**
 * One exercise of a logged session, in the order it was performed.
 *
 * @property supersetGroup which superset this exercise belongs to within its session,
 *   or null when it stands alone. See [com.titan.core.domain.superset.Supersets] for
 *   what a group means and the rules it has to satisfy.
 */
data class SessionExercise(
    val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val position: Int,
    val supersetGroup: Int? = null,
    val templateExerciseId: Long? = null,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
