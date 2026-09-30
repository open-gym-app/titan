package com.titan.core.data.db.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.titan.core.data.db.entity.ExerciseEntity

/**
 * One mobility exercise joined to one of its prime movers, produced by
 * [com.titan.core.data.db.dao.ExerciseDao.getMobilityExercises].
 *
 * Flat rather than nested because that is what the join produces: an exercise with two primary
 * links is two rows, and the repository collapses them. Room's `@Relation` would express the
 * nesting directly but runs a second query per parent and cannot be used inside a `@Query`
 * projection without one — see `getAllMuscleGroupLinks`, which flattens for the same reason.
 *
 * [isDynamic] rides along rather than being a second read of `exercise_tag`: it is one
 * `EXISTS` per exercise in a query that is already walking that table's index, where a separate
 * tag query would be a second Flow to combine and a second chance for the two to disagree about
 * which exercises are in the catalogue.
 *
 * @property muscleGroupId the `muscle_group` row this exercise moves as the prime mover. Only
 *   the id, because nothing here draws the muscle: the warm-up card names the *exercise*, and
 *   the id is only ever matched against the session's own links.
 */
data class MobilityExerciseRow(
    @Embedded
    val exercise: ExerciseEntity,
    @ColumnInfo(name = "muscle_group_id")
    val muscleGroupId: Long,
    @ColumnInfo(name = "is_dynamic")
    val isDynamic: Boolean,
)
