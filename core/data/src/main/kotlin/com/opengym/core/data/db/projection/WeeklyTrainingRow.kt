package com.opengym.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * One bucket of the consistency series, produced by
 * [com.opengym.core.data.db.dao.WorkoutSessionDao.getTrainingByWeek]. Both counts are
 * computed in SQL, so a multi-year history never materialises as session rows.
 *
 * [weekStartEpochDay] is the Monday (UTC) that opens the bucket, expressed as days since
 * the epoch — the same bucketing [WeeklyVolumeRow] uses, so the two series line up week
 * for week.
 *
 * The two counts differ for anybody who finishes more than one session in a day, and the
 * smaller one is what consistency is measured with:
 *
 *  - [trainingDays] is `COUNT(DISTINCT day)`, one to seven. A lifter who benches in the
 *    morning and runs in the evening trained on one day.
 *  - [sessionCount] is the raw row count, which is at least [trainingDays].
 */
data class WeeklyTrainingRow(
    @ColumnInfo(name = "week_start")
    val weekStartEpochDay: Long,
    @ColumnInfo(name = "training_days")
    val trainingDays: Int,
    @ColumnInfo(name = "session_count")
    val sessionCount: Int,
)
