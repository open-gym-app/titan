package com.titan.core.domain.model

import kotlinx.serialization.Serializable

/**
 * One set as a plan says it should be performed: what kind of set it is, and the two
 * figures a lifter reads off it.
 *
 * ### Why a plan holds a *list* of these rather than three numbers
 *
 * It used to hold three: `defaultSets`, `defaultReps`, `defaultWeight` on the exercise
 * itself. That shape can only express "four sets of eight at 80", which is not how
 * anybody trains — a warm-up is lighter, the working sets are not always the same
 * weight, and a drop set is a fourth thing again. A template that can only plan
 * identical sets forces every plan through the one case, and the sets it cannot
 * express are the ones the user came to the editor to write down.
 *
 * So the plan is a list, in the order the sets are performed, and each entry says what
 * *that* set is. It is the same list the session draws, which is the other half of the
 * point: a template is a workout somebody has not done yet, and the two screens should
 * be showing the same thing.
 *
 * ### What it deliberately leaves out
 *
 * **The number.** A set's number is its index in the list — the same rule
 * [TemplateExerciseDraft] and [ActiveSet] follow, and for the same reason: a list is
 * already ordered, and a caller that hand-numbers is one insertion away from a
 * duplicate. The write paths number it.
 *
 * **The rating.** [WorkoutSet.rpe] records how hard a set *was*, which is a reading
 * taken after performing it. A plan that pre-filled it would put a measurement in the
 * user's mouth, and a session started from that template would open with every set
 * already claiming to have felt like an RPE 8.
 *
 * **The rest.** Rest belongs to the exercise ([TemplateExercise.restDuration]), not to
 * each of its sets: it is one number a lifter sets once, and per-set rest would be a
 * column of identical figures for every template anybody actually writes.
 *
 * @property setType what kind of set this is — the warm-up, the working sets and the
 *   drop set the old shape could not tell apart. Carried into the session as the set's
 *   own type, so a planned warm-up is already a warm-up when it is ticked off, and is
 *   already excluded from the previous-performance reference the way a logged one is.
 * @property reps how many, or null where the plan declines to say. Null is a real
 *   answer and the ordinary one for a set somebody has added but not priced yet — the
 *   session opens it as an empty field rather than as a target.
 * @property weight in milligrams, matching [WorkoutSet.weight], or null for a set
 *   planned without one — bodyweight work, or a figure the lifter decides on the day.
 *
 * `@Serializable` because a planned set travels inside [SessionPlan] and [TemplateDraft], both
 * of which are written down while the user is still deciding — see #183.
 */
@Serializable
data class PlannedSet(
    val setType: SetType = SetType.NORMAL,
    val reps: Int? = null,
    val weight: Long? = null,
)
