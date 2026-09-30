package com.titan.core.domain.usecase

import com.titan.core.domain.model.TemplateExerciseDraft

/**
 * A template that has passed every rule in [validateTemplate], in the form it is to be
 * stored: the trimmed [name], and the [exercises] with their superset grouping
 * normalised.
 *
 * Both halves are carried because validation *rewrites* both, and a use case that
 * stored the values it was handed rather than these would persist the untrimmed name
 * or a superset of one.
 */
internal data class ValidTemplate(
    val name: String,
    val exercises: List<TemplateExerciseDraft>,
)
