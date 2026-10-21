package com.titan.core.domain.model

/**
 * A saved exercise sequence.
 *
 * What a template row does *not* hold is as deliberate as what it does: there is no
 * estimated duration and no exercise count. Both are read off the template's exercises
 * — see [com.titan.core.domain.template.TemplateDuration] — because a stored copy
 * goes stale the moment the template is edited, and a screen showing "8 ex · ~55m"
 * against a body that no longer matches is worse than one that computes it.
 */
data class WorkoutTemplate(
    val id: Long = 0,
    val name: String,
    val category: TemplateCategory? = null,
    val icon: String? = null,
)
