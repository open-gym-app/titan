package com.opengym.core.domain.model

data class WorkoutSet(
    val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val setOrder: Int,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val durationSeconds: Long? = null,
    val distanceMeters: Double? = null,
    val notes: String = "",
)
