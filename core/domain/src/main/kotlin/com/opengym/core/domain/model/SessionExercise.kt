package com.opengym.core.domain.model

data class SessionExercise(
    val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val position: Int,
    val templateExerciseId: Long? = null,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
