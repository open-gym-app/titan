package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical storage units: weight in milligrams, distance in centimeters,
 * duration/rest_after in seconds, rpe as RPE x 10 (range 10-100), and every
 * timestamp column (`completed_at`, `created_at`, `updated_at`) in epoch
 * milliseconds. The rpe range and set_type values are enforced by the domain
 * layer (Room cannot express SQL CHECK constraints).
 */
@Entity(
    tableName = "workout_set",
    foreignKeys = [
        ForeignKey(
            entity = SessionExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("session_exercise_id"),
        Index("is_completed"),
        // The analytics aggregates (weekly volume, volume by muscle group) scan
        // a date range over the whole table, so completed_at needs its own
        // index — without it every trend query is a full scan of all history.
        Index("completed_at"),
    ],
)
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_exercise_id")
    val sessionExerciseId: Long,
    @ColumnInfo(name = "set_number")
    val setNumber: Int,
    @ColumnInfo(name = "reps")
    val reps: Int? = null,
    @ColumnInfo(name = "weight")
    val weight: Long? = null,
    @ColumnInfo(name = "duration")
    val duration: Long? = null,
    @ColumnInfo(name = "distance")
    val distance: Long? = null,
    @ColumnInfo(name = "heart_rate_avg")
    val heartRateAvg: Int? = null,
    @ColumnInfo(name = "heart_rate_max")
    val heartRateMax: Int? = null,
    @ColumnInfo(name = "rest_after")
    val restAfter: Long? = null,
    @ColumnInfo(name = "rpe")
    val rpe: Int? = null,
    @ColumnInfo(name = "set_type", defaultValue = "NORMAL")
    val setType: String = "NORMAL",
    @ColumnInfo(name = "completed_at")
    val completedAt: Long,
    @ColumnInfo(name = "is_completed", defaultValue = "0")
    val isCompleted: Boolean = false,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
