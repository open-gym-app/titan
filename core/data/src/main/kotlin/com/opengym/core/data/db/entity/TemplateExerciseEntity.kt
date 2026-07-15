package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "template_exercise",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("template_id"),
        Index(value = ["template_id", "position"], unique = true),
        Index("exercise_id"),
    ],
)
data class TemplateExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "template_id")
    val templateId: Long,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "position")
    val position: Int,
    @ColumnInfo(name = "default_sets")
    val defaultSets: Int? = null,
    @ColumnInfo(name = "default_reps")
    val defaultReps: Int? = null,
    @ColumnInfo(name = "default_weight")
    val defaultWeight: Long? = null,
    @ColumnInfo(name = "rest_duration")
    val restDuration: Long? = null,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
)
