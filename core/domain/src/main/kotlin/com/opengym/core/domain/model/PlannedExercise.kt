package com.opengym.core.domain.model

/**
 * The three facts about a planned exercise that decide how long it takes.
 *
 * Implemented by [TemplateExercise] and [TemplateExerciseDraft] so
 * [com.opengym.core.domain.template.TemplateDuration] can estimate a stored template
 * and one still being edited with the same function — a template's duration is shown
 * on a row that reads it back and in an editor that has not saved it yet, and those
 * must not be two rules that can drift.
 *
 * It carries no `position`: the two types number positions differently (a stored row
 * has the column, a draft takes its position from list order), so an estimate works on
 * the list in position order and its indices, exactly as
 * [com.opengym.core.domain.superset.Supersets] does.
 */
interface PlannedExercise {

    /** Planned sets, or null when the template does not say. */
    val defaultSets: Int?

    /** Seconds of rest after a set of this exercise, or null to take the caller's default. */
    val restDuration: Long?

    /** The superset this exercise belongs to, or null when it stands alone. */
    val supersetGroup: Int?
}
