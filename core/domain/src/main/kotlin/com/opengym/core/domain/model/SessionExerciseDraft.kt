package com.opengym.core.domain.model

/**
 * One exercise of a session as the caller supplies it, before the rows exist.
 *
 * The session counterpart of [TemplateExerciseDraft], and it leaves out the same
 * things for the same reasons: `id` and `sessionId` are not known until the session
 * row is written, and `position` is taken from the order of the list rather than from
 * a field, because `session_exercise` carries a unique `(session_id, position)` index
 * and a hand-numbered list is one reorder away from violating it.
 *
 * [supersetGroup] is subject to [com.opengym.core.domain.superset.Supersets]' rules,
 * read over the list this draft sits in — so the list must be in the order the
 * exercises will be performed. The write path normalises the grouping, so a caller may
 * hand over a group left with a single member after a removal without repairing it
 * first.
 *
 * @property templateExerciseId the `template_exercise` row this came from, or null for
 *   an exercise the user picked themselves. It is a provenance link and nothing more:
 *   editing or deleting the template afterwards does not change the session, and the
 *   column is `SET NULL` when the row it points at goes away.
 */
data class SessionExerciseDraft(
    val exerciseId: Long,
    val supersetGroup: Int? = null,
    val templateExerciseId: Long? = null,
    val notes: String? = null,
)
