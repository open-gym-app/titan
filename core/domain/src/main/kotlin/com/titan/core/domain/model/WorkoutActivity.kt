package com.titan.core.domain.model

/**
 * What kind of workout a session was, in the vocabulary Health Connect files sessions under.
 *
 * A Titan session has no such field of its own: it is a list of exercises, and what it *was*
 * follows from them — see [of]. The value is needed at two edges only. On the way out it is the
 * `exerciseType` of the `ExerciseSessionRecord` a finished session is written as; on the way in
 * it is what an imported session is stored with, because a session that came from another app
 * has no exercises to work it out from, and the import's duplicate check compares it.
 *
 * Deliberately coarser than Health Connect's own list of some eighty types. Every constant here is
 * one somebody might plausibly have logged in another app *and* want to see in their history;
 * the rest arrive as [OTHER], which keeps the session and its times and loses only a label the
 * history screen would not have drawn differently anyway.
 *
 * Persisted by name in `workout_session.health_connect_activity`, so a constant is never renamed —
 * `PersistedEnumTest` pins them.
 */
enum class WorkoutActivity {
    STRENGTH_TRAINING,
    WEIGHTLIFTING,
    CALISTHENICS,
    STRETCHING,
    YOGA,
    PILATES,
    HIGH_INTENSITY_INTERVAL_TRAINING,
    RUNNING,
    WALKING,
    HIKING,
    BIKING,
    SWIMMING,
    ROWING,
    ELLIPTICAL,
    OTHER,
    ;

    companion object {

        /**
         * What a session logged in Titan was, from the [Discipline]s of the exercises it
         * completed a set of.
         *
         * Only four answers are reachable, and on purpose. A discipline says which tradition a
         * movement comes from, not which machine it was done on: [Discipline.CARDIO] covers a
         * treadmill run, a rower and a stair climber alike, so there is nothing to tell
         * [RUNNING] from [ROWING] by and guessing would write a wrong label into another app.
         *
         * - Every exercise [Discipline.STRETCHING]: [STRETCHING].
         * - Every exercise [Discipline.CARDIO]: [OTHER], for the reason above.
         * - Every exercise [Discipline.POWERLIFTING] or [Discipline.OLYMPIC_WEIGHTLIFTING]:
         *   [WEIGHTLIFTING].
         * - Anything else, a mix included: [STRENGTH_TRAINING]. A session that is mostly
         *   lifting with a stretch at the end is a lifting session, and an exercise with no
         *   discipline — a custom one — is one the user made in a weightlifting log.
         *
         * An empty collection is [STRENGTH_TRAINING] too. A finished session always has a set,
         * so it can only come from a session somebody else's code closed out without one.
         */
        fun of(disciplines: Collection<Discipline?>): WorkoutActivity =
            when {
                disciplines.isEmpty() -> STRENGTH_TRAINING

                disciplines.all { it == Discipline.STRETCHING } -> STRETCHING

                disciplines.all { it == Discipline.CARDIO } -> OTHER

                disciplines.all { it == Discipline.POWERLIFTING || it == Discipline.OLYMPIC_WEIGHTLIFTING } ->
                    WEIGHTLIFTING

                else -> STRENGTH_TRAINING
            }
    }
}
