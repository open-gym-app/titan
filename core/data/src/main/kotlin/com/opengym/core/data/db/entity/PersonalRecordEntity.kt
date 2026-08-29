package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Derived data: a record always points at the set that achieved it, so both
 * foreign keys cascade — deleting the source data deletes the record.
 * weight/estimated_1rm in milligrams, distance in centimeters, duration in
 * seconds, achieved_at in epoch milliseconds. `weight` doubles as the
 * milligram-reps total for a `MAX_VOLUME` row — see
 * [com.opengym.core.domain.model.PersonalRecord] for why there is no separate
 * column for it.
 */
@Entity(
    tableName = "personal_record",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkoutSetEntity::class,
            parentColumns = ["id"],
            childColumns = ["workout_set_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["exercise_id", "achieved_at"]),
        Index("workout_set_id"),
        // getRecentRecords orders by achieved_at across all exercises, which the
        // composite index above can't serve (achieved_at isn't its left prefix).
        Index("achieved_at"),
    ],
)
data class PersonalRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "workout_set_id")
    val workoutSetId: Long,
    @ColumnInfo(name = "pr_type")
    val prType: String,
    @ColumnInfo(name = "weight")
    val weight: Long? = null,
    @ColumnInfo(name = "reps")
    val reps: Int? = null,
    @ColumnInfo(name = "distance")
    val distance: Long? = null,
    @ColumnInfo(name = "duration")
    val duration: Long? = null,
    @ColumnInfo(name = "estimated_1rm")
    val estimated1Rm: Long? = null,
    @ColumnInfo(name = "achieved_at")
    val achievedAt: Long,
)
