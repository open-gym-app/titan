package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_session",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("is_in_progress"),
        Index("created_at"),
        Index("last_active_at"),
        Index("template_id"),
        // Unique, and what makes a second import of the same record a no-op even when two
        // imports race: `INSERT OR IGNORE` keeps whichever lands first. NULL for every session
        // logged here, and SQLite treats NULLs as distinct, so those never collide.
        Index(value = ["health_connect_id"], unique = true),
    ],
)
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "template_id")
    val templateId: Long? = null,
    /** Nullable on purpose — see [com.titan.core.domain.model.WorkoutSession.name]. */
    @ColumnInfo(name = "name")
    val name: String? = null,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "is_in_progress", defaultValue = "1")
    val isInProgress: Boolean = true,
    @ColumnInfo(name = "last_active_at")
    val lastActiveAt: Long,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** See [com.titan.core.domain.model.WorkoutSession.healthConnectId]. */
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String? = null,
    /**
     * A [com.titan.core.domain.model.WorkoutActivity] name, for an imported session only. See
     * [com.titan.core.domain.model.WorkoutSession.healthConnectActivity].
     */
    @ColumnInfo(name = "health_connect_activity")
    val healthConnectActivity: String? = null,
)
