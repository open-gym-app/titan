package com.opengym.core.domain.model

/**
 * One row of `exercise_muscle_group`: a [MuscleGroup] an exercise trains, and
 * whether it trains it as the primary mover.
 *
 * The flag travels with the group rather than being recoverable from a second
 * query, because every consumer that cares which muscles an exercise works also
 * cares how hard — attribution ([com.opengym.core.domain.stats.MuscleVolumeCalculator])
 * weights a primary link differently from a secondary one, and an exercise detail
 * screen renders the two differently. Reading the groups and then the flags would
 * be two passes over the same join.
 *
 * The seed guarantees exactly one primary link per exercise (see PERSISTENCE.md),
 * but nothing here depends on that: a link is scored on its own flag, so a
 * user-authored exercise with two primaries or none still attributes sensibly.
 */
data class ExerciseMuscleGroupLink(
    val muscleGroup: MuscleGroup,
    val isPrimary: Boolean,
)
