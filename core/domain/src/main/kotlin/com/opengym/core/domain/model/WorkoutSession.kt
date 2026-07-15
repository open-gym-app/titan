package com.opengym.core.domain.model

data class WorkoutSession(
    val id: Long = 0,
    val templateId: Long? = null,
    val completedAt: Long? = null,
    val isInProgress: Boolean = true,
    val lastActiveAt: Long,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
