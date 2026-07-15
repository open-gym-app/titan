package com.opengym.core.domain.model

/**
 * [weight] is stored in milligrams (canonical unit, no floating point).
 */
data class BodyWeightLog(
    val id: Long = 0,
    val weight: Long,
    val notes: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
