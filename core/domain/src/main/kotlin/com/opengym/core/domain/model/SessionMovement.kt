package com.opengym.core.domain.model

/**
 * One exercise of a session as [com.opengym.core.domain.warmup.WarmupPlanner] reads it: what it
 * is, what it trains, and what it is about to be loaded with.
 *
 * Three facts assembled from three different places — the catalogue row, the muscle join, and
 * the session's own sets — which is why the planner takes this rather than the session. It has
 * no ids of its own for the same reason [PlannedExercise] has no position: what the planner
 * reads is a *list in performance order*, and the one id it hands back is the catalogue one on
 * [exercise].
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
 */
data class SessionMovement(
    val exercise: Exercise,
    val muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    val workingWeight: Long? = null,
)
