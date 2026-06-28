package com.opengym.core.domain.model

data class WorkoutSession(
    val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val notes: String = "",
)
