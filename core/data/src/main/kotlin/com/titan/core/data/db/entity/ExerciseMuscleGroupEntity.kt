package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "exercise_muscle_group",
    primaryKeys = ["exercise_id", "muscle_group_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MuscleGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["muscle_group_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    // Only muscle_group_id is indexed explicitly: the composite primary key
    // (exercise_id, muscle_group_id) already provides a left-prefix index that
    // covers exercise_id lookups, so a separate exercise_id index would be
    // redundant. muscle_group_id needs its own index for the reverse FK.
    indices = [Index("muscle_group_id")],
)
data class ExerciseMuscleGroupEntity(
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "muscle_group_id")
    val muscleGroupId: Long,
    @ColumnInfo(name = "is_primary", defaultValue = "1")
    val isPrimary: Boolean = true,
)
