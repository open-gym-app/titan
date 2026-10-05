package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * A seeded exercise's id under the key the default templates name it by — see
 * [com.titan.core.data.db.dao.ExerciseDao.getSeededByName].
 */
data class SeededExerciseKeyRow(
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "equipment_type")
    val equipmentType: String,
)
