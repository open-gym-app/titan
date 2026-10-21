package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One exercise of a template. What it plans is in [TemplateSetEntity], one row per set:
 * the `default_sets`, `default_reps` and `default_weight` columns that used to live here
 * could only describe identical sets, which is not a plan anybody trains from.
 *
 * There is no rest column: the workout's timer runs the exercise's own `default_rest` or
 * the user's setting, whatever the template says — see
 * [com.titan.core.domain.timer.RestDurations].
 */
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
    /** The superset this exercise is part of, or NULL when it stands alone. */
    @ColumnInfo(name = "superset_group")
    val supersetGroup: Int? = null,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
)
