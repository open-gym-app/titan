package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * One bucket of the weekly volume trend, produced by
 * [com.titan.core.data.db.dao.WorkoutSetDao.getVolumeByWeek]. The sum is
 * computed in SQL so a multi-year history never materialises as set rows.
 *
 * [weekStartEpochDay] is the Monday (UTC) that opens the bucket, expressed as
 * days since the epoch. [totalVolume] is in milligrams, the canonical weight
 * unit (`weight x reps`), so a 100 kg x 10 set contributes 1_000_000_000.
 */
data class WeeklyVolumeRow(
    @ColumnInfo(name = "week_start")
    val weekStartEpochDay: Long,
    @ColumnInfo(name = "total_volume")
    val totalVolume: Long,
)
