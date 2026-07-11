package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "exercise",
    indices = [
        Index("name"),
        Index("category"),
        Index("exercise_type"),
    ],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "exercise_type", defaultValue = "STRENGTH")
    val exerciseType: String = "STRENGTH",
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "equipment_type")
    val equipmentType: String? = null,
    @ColumnInfo(name = "mechanic_type")
    val mechanicType: String? = null,
    @ColumnInfo(name = "difficulty_level")
    val difficultyLevel: String? = null,
    @ColumnInfo(name = "is_unilateral", defaultValue = "0")
    val isUnilateral: Boolean = false,
    @ColumnInfo(name = "default_rest")
    val defaultRest: Long? = null,
    @ColumnInfo(name = "description")
    val description: String? = null,
    @ColumnInfo(name = "instructions")
    val instructions: String? = null,
    @ColumnInfo(name = "is_custom", defaultValue = "0")
    val isCustom: Boolean = false,
    @ColumnInfo(name = "icon")
    val icon: String? = null,
)
