package com.opengym.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * Volume attributed to a single muscle group over a date range, produced by
 * [com.opengym.core.data.db.dao.WorkoutSetDao.getVolumeByMuscleGroup].
 *
 * Two sums are returned because callers want both readings and neither is
 * derivable from the other once the rows are collapsed:
 *
 *  - [totalVolume] credits every linked muscle group with the *whole* volume of
 *    the set (`weight x reps`, milligrams). Summing this column across muscle
 *    groups therefore exceeds total training volume - that is expected, a set
 *    trains several muscles at once.
 *  - [weightedVolume] applies the same attribution rule as the domain-side
 *    `MuscleVolumeCalculator`: 100% to a primary link, 50% to a secondary one.
 *
 * Either way a set contributes to a given muscle group exactly once, however
 * many secondary muscles the exercise also trains.
 */
data class MuscleVolumeRow(
    @ColumnInfo(name = "muscle_group_id")
    val muscleGroupId: Long,
    @ColumnInfo(name = "muscle_group_name")
    val muscleGroupName: String,
    @ColumnInfo(name = "total_volume")
    val totalVolume: Long,
    @ColumnInfo(name = "weighted_volume")
    val weightedVolume: Long,
)
