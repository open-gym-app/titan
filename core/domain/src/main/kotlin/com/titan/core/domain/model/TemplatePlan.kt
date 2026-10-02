package com.titan.core.domain.model

/**
 * A template read out as the plan for a session that has not been started yet.
 *
 * Everything [com.titan.core.domain.usecase.StartSessionUseCase] and the screen
 * behind it need from the template, in one read: the [name] the new session takes as
 * its own, and the [exercises] it is laid out with. The template row itself is not
 * carried — a plan is a snapshot taken at apply time, and handing back the live
 * template would invite a caller to write through it.
 *
 * @property templateId the template this was read from, which the session records so
 *   history can say where it came from.
 * @property name the template's name at apply time. The session copies it rather than
 *   pointing at it: renaming a template afterwards must not rewrite the title of a
 *   workout already logged under the old one, and the user is free to edit the
 *   session's own name without touching the template.
 * @property exercises the template's exercises, ordered by position, each with the sets it
 *   plans — see [PlannedSet]. Those sets are what the session is laid out with, so a plan that
 *   dropped them would start a workout with nothing in it.
 */
data class TemplatePlan(
    val templateId: Long,
    val name: String,
    val exercises: List<TemplateExercise>,
)
