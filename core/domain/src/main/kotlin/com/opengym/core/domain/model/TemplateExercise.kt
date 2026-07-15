package com.opengym.core.domain.model

data class TemplateExercise(
    val id: Long = 0,
    val templateId: Long,
    val exerciseId: Long,
    val position: Int,
    val defaultSets: Int? = null,
    val defaultReps: Int? = null,
    val defaultWeight: Long? = null,
    val restDuration: Long? = null,
    val notes: String? = null,
)
