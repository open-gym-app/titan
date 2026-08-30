package com.opengym.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * How many records were broken over a date range, and across how many exercises, produced by
 * [com.opengym.core.data.db.dao.PersonalRecordDao.getRecordTally].
 *
 * Two counts in one row because the progression screen draws them as one figure and its
 * caption — `7` over `across 5 ex.` — and reading them separately would be two scans of the
 * same slice of the same index to answer one question.
 *
 * @property recordCount rows achieved in the range. Per **record**, not per session: a workout
 *   that beat its weight, rep and volume bests for one lift wrote three, and it emitted three
 *   `XpEvent.PrBroken` events to match.
 * @property exerciseCount distinct exercises those rows belong to. Always at least one when
 *   [recordCount] is, and lower whenever a lifter had a good day on a single movement.
 */
data class RecordTallyRow(
    @ColumnInfo(name = "record_count")
    val recordCount: Int,
    @ColumnInfo(name = "exercise_count")
    val exerciseCount: Int,
)
