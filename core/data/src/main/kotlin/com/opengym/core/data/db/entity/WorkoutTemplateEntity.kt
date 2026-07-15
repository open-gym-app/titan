package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_template")
data class WorkoutTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "icon")
    val icon: String? = null,
)
