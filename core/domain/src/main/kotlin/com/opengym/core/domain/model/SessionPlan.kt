package com.opengym.core.domain.model

/**
 * A workout that has been decided on but not started.
 *
 * What the new-session flow assembles and what
 * [com.opengym.core.domain.usecase.StartPlannedSessionUseCase] turns into rows. It is
 * deliberately a plain value with no id in it: **a plan is not a session**, and until
 * the user commits to it nothing has been written — which is what lets backing out of
 * the flow cost nothing to undo, since there is nothing to undo.
 *
 * The counterpart of [TemplatePlan] on the other side of the same decision: that one is
 * a template read *out*, this one is a session about to be written *in*. A plan built
 * from a template is [TemplatePlan.toSessionPlan]; a plan built by picking exercises
 * is [fromExercises].
 *
 * @property exercises the exercises in the order they will be performed. Empty is
 *   legal — a session the user fills in as they go is a session — and the flow's own
 *   START is what decides whether to offer it.
 * @property templateId the template this came from, recorded on the session so history
 *   can say where it came from, or null for one built by hand.
 * @property name what to call the session, or null to leave it genuinely unnamed and
 *   let the UI fall back to the date. See [WorkoutSession.name].
 */
data class SessionPlan(
    val exercises: List<SessionExerciseDraft>,
    val templateId: Long? = null,
    val name: String? = null,
) {

    companion object {

        /**
         * A plan over [exerciseIds], in the order they were picked, with no supersets
         * and no name.
         *
         * The exercise picker's result is a list of catalogue ids and nothing else, so
         * this is the whole of the translation — worth naming rather than inlining
         * because "picked order becomes session order" is the part a caller could get
         * wrong silently.
         */
        fun fromExercises(exerciseIds: List<Long>): SessionPlan =
            SessionPlan(exercises = exerciseIds.map { SessionExerciseDraft(exerciseId = it) })
    }
}

/**
 * This template's plan as a session about to be started from it.
 *
 * The name and the superset grouping carry over — that is what "a session started from
 * a template *is* that template" means — along with the link back to each
 * [TemplateExercise], so a session can say which template line each of its exercises
 * came from.
 *
 * The per-exercise defaults (sets, reps, weight, rest) deliberately do **not** come
 * with it: `session_exercise` has no columns for them. They are defaults for the *sets*
 * the user is about to log, which is the active session's business
 * ([com.opengym.core.domain.model.ActiveSession]) and not something the row being
 * written here can hold.
 */
fun TemplatePlan.toSessionPlan(): SessionPlan = SessionPlan(
    exercises = exercises.map { exercise ->
        SessionExerciseDraft(
            exerciseId = exercise.exerciseId,
            supersetGroup = exercise.supersetGroup,
            templateExerciseId = exercise.id,
        )
    },
    templateId = templateId,
    name = name,
)
