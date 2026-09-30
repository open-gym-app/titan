package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * A `muscle_group` row joined to the `exercise_muscle_group` row that links it to
 * one exercise, produced by
 * [com.titan.core.data.db.dao.ExerciseDao.getMuscleGroupLinks].
 *
 * A projection rather than a plain `MuscleGroupEntity` because [isPrimary] belongs
 * to the join row, not to the muscle group: the same group is primary for one
 * exercise and secondary for the next. Callers that weight the two differently -
 * the domain's `MuscleVolumeCalculator` above all - would otherwise need a second
 * query to recover the flag.
 */
data class ExerciseMuscleGroupLinkRow(
    @ColumnInfo(name = "muscle_group_id")
    val muscleGroupId: Long,
    @ColumnInfo(name = "muscle_group_name")
    val muscleGroupName: String,
    @ColumnInfo(name = "muscle_group_icon")
    val muscleGroupIcon: String?,
    @ColumnInfo(name = "is_primary")
    val isPrimary: Boolean,
)
