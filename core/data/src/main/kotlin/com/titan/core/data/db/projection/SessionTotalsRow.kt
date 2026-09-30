package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * One session's roll-up, produced by
 * [com.titan.core.data.db.dao.WorkoutSetDao.getTotalsBySession].
 *
 * The sums run in SQLite, so the result grows with the number of *sessions* rather than the
 * number of sets — which is the whole reason the history list can show a volume per row
 * without loading anybody's sets.
 *
 * [totalVolume] is in milligram-reps (`weight x reps`), the canonical weight unit, so a
 * 100 kg x 10 set contributes 1_000_000_000.
 */
data class SessionTotalsRow(
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "total_sets")
    val totalSets: Int,
    @ColumnInfo(name = "total_volume")
    val totalVolume: Long,
)
