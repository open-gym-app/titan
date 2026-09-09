package com.opengym.core.domain.model

import kotlinx.serialization.Serializable

/**
 * A workout template as it is being written, before anybody has pressed SAVE.
 *
 * The template editor holds a *draft*: nothing it shows is stored until the whole plan is
 * written at once, because rearranging a plan is a sequence of decisions the user is entitled to
 * back out of. That makes the half-built template the most expensive thing on any screen in the
 * app to lose — several exercises, their sets and their figures, which is minutes of work — and
 * the reason it is written down here rather than left in a `Bundle`. Saved instance state
 * survives the process; it does not survive the task record going with it, and this does.
 *
 * ### Why it is not the stored shape
 *
 * [TemplateExerciseDraft] is what a *write* takes, and every figure on it is parsed: a `reps` of
 * `Int?` and a `weight` of `Long?`. A draft is the other thing — what the user has typed so far,
 * including the minus sign they are halfway through and the empty box they have not filled in —
 * so [TemplateDraftSet] keeps text. The two meet when SAVE parses one into the other.
 *
 * ### Why the unit system travels with it
 *
 * Every weight here is text typed in one unit, and the editor reads the setting once at open and
 * sticks with it for exactly that reason: reformatting a column of figures under somebody
 * mid-edit rewrites what they typed. A draft restored a day later has to keep that promise, so
 * it carries the unit its numbers were typed in rather than adopting whatever the setting says
 * when it is read back.
 *
 * @param templateId the template being changed, or `0` for one that does not exist yet — the
 *   same convention an unwritten row id follows everywhere else here. It is what makes the draft
 *   a *slot* rather than a collection: at most one template is being edited at a time, and a
 *   draft that names a different one belongs to a screen the user has already left.
 * @param icon not edited by the editor and carried anyway, so that saving a restored draft does
 *   not clear a field nothing on the screen ever showed.
 */
@Serializable
data class TemplateDraft(
    val templateId: Long,
    val name: String = "",
    val category: TemplateCategory? = null,
    val icon: String? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val exercises: List<TemplateDraftExercise> = emptyList(),
)

/**
 * One line of a draft plan.
 *
 * @param key identity across a reorder, stable for the life of the draft. Not the stored
 *   `TemplateExercise.id`, which a line added since the last save does not have — the editor
 *   counts those down from -1 over the stored ids, and saving the keys is what lets a restored
 *   draft go on counting from where it left off rather than handing out one that is already
 *   taken.
 * @param restDuration seconds, per [TemplateExercise.restDuration]. Not edited by the editor and
 *   carried for [TemplateDraft.icon]'s reason.
 * @param notes not edited by the editor and carried for [TemplateDraft.icon]'s reason.
 */
@Serializable
data class TemplateDraftExercise(
    val key: Long,
    val exerciseId: Long,
    val sets: List<TemplateDraftSet> = emptyList(),
    val supersetGroup: Int? = null,
    val restDuration: Long? = null,
    val notes: String? = null,
)

/**
 * One planned set of a draft line, as it is being typed.
 *
 * @param key identity within its exercise, so a keystroke that lands after a set was inserted
 *   above still reaches the set it was typed into.
 * @param weight what is in the box, in [TemplateDraft.unitSystem]'s unit. A `String` because a
 *   half-typed number is not a number, and a field that parsed on every keystroke would delete
 *   the character somebody was in the middle of.
 * @param reps what is in the other box, on the same terms.
 */
@Serializable
data class TemplateDraftSet(
    val key: Long,
    val setType: SetType = SetType.NORMAL,
    val weight: String = "",
    val reps: String = "",
)
