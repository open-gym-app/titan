package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "exercise",
    indices = [
        Index("name"),
        Index("equipment_type"),
        Index("exercise_type"),
        // A movement is identified by name + equipment + (for machines) brand, so PRs
        // stay separate per machine. NULL brand doesn't collide with other NULLs, so
        // non-machine equipment (barbell, dumbbell, bodyweight...) is unaffected.
        Index(value = ["name", "equipment_type", "machine_brand"], unique = true),
    ],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "exercise_type", defaultValue = "STRENGTH")
    val exerciseType: String = "STRENGTH",
    @ColumnInfo(name = "equipment_type")
    val equipmentType: String? = null,
    @ColumnInfo(name = "machine_brand")
    val machineBrand: String? = null,
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
