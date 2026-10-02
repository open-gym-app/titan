package com.titan.core.domain.model

/**
 * One exercise of a session as [com.titan.core.domain.warmup.WarmupPlanner] reads it: what it
 * is, what it trains, and what it is about to be loaded with.
 *
 * Three facts assembled from three different places — the catalogue row, the muscle join, and
 * the session's own sets — which is why the planner takes this rather than the session. It
 * otherwise has no ids of its own for the same reason [PlannedExercise] has no position: what
 * the planner reads is a *list in performance order*, and the one id it hands back for display
 * is the catalogue one on [exercise].
 *
 * @property exercise the catalogue row. Its [Exercise.mechanicType] is what decides whether the
 *   ramp-up sets are built for it, and its [Exercise.equipmentType] is what they are rounded
 *   through.
 * @property muscleGroups its links, primary and secondary. Both are read: the primary ones say
 *   what the session is *for*, and the secondary ones widen the net a mobility movement is
 *   matched against — a bench press warms up with shoulder work, and shoulders are a secondary
 *   link of the bench press rather than a primary one.
 * @property workingWeight what this exercise's working sets are expected to be performed at, in
 *   milligrams, or **null** where nothing says — a bodyweight movement, a cardio machine, or an
 *   exercise the lifter has neither planned nor performed before. Null is the ordinary answer
 *   and simply means no ramp-up can be built for this one.
 * @property sessionExerciseId the `session_exercise` row this movement came from. The planner
 *   never branches on it — the muscle and ramp-up logic only ever reads [exercise] and
 *   [workingWeight] — but it travels through to the ramp-up answer, because the same catalogue
 *   exercise can occupy more than one row of a session (two bench slots either side of an
 *   accessory) and [exercise]'s id alone cannot say which row a ramp-up was computed for.
 *   Defaults to the catalogue id for fixtures that never put the same exercise in a session twice.
 */
data class SessionMovement(
    val exercise: Exercise,
    val muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    val workingWeight: Long? = null,
    val sessionExerciseId: Long = exercise.id,
)
