package com.opengym.core.data.db.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.opengym.core.data.db.entity.WorkoutSetEntity

/**
 * One set of a *previous* session, carrying the catalogue exercise it was performed on.
 *
 * Produced by [com.opengym.core.data.db.dao.WorkoutSetDao.getPreviousSetsForSession], which is
 * the one query in this file's neighbourhood that returns rows from several sessions at once.
 * That is what the extra column is for: a [WorkoutSetEntity] names its `session_exercise_id`,
 * and the caller is holding *this* session's exercise rows, so the id on the row resolves to
 * nothing it knows about. The catalogue id is the key both sides share.
 */
data class PreviousSetRow(
    @Embedded
    val set: WorkoutSetEntity,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
)
