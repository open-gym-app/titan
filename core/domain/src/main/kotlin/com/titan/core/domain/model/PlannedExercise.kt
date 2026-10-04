package com.titan.core.domain.model

/**
 * The facts about a planned exercise that decide how long it takes.
 *
 * Implemented by [TemplateExercise] and [TemplateExerciseDraft] so
 * [com.titan.core.domain.template.TemplateDuration] can estimate a stored template
 * and one still being edited with the same function — a template's duration is shown
 * on a row that reads it back and in an editor that has not saved it yet, and those
 * must not be two rules that can drift.
 *
 * It carries no `position`: the two types number positions differently (a stored row
 * has the column, a draft takes its position from list order), so an estimate works on
 * the list in position order and its indices, exactly as
 * [com.titan.core.domain.superset.Supersets] does.
 */
interface PlannedExercise {

    /**
     * The catalogue exercise it plans. Read by the estimate for the exercise's type, which
     * decides whether a set's planned duration is one the user can see — see
     * [com.titan.core.domain.template.TemplateDuration].
     */
    val exerciseId: Long

    /**
     * The sets planned for it, in the order they are performed.
     *
     * A list rather than a count, because the estimate is not the only reader: what a
     * template plans is a *sequence* — a warm-up, three working sets, a drop set — and
     * a count could not tell those apart. See [PlannedSet].
     *
     * Empty is legal and means the plan says nothing about this exercise's sets, which
     * is what an exercise the user has added but not priced looks like.
     */
    val sets: List<PlannedSet>

    /** Seconds of rest after a set of this exercise, or null to take the caller's default. */
    val restDuration: Long?

    /** The superset this exercise belongs to, or null when it stands alone. */
    val supersetGroup: Int?
}
