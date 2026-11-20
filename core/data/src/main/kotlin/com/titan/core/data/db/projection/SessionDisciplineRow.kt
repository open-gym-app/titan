package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * One exercise done in one session, by discipline, produced by
 * [com.titan.core.data.db.dao.WorkoutSessionDao.getPerformedDisciplines].
 *
 * @property discipline a [com.titan.core.domain.model.Discipline] name, or null for an exercise
 *   that has none.
 */
data class SessionDisciplineRow(
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "discipline")
    val discipline: String?,
)
