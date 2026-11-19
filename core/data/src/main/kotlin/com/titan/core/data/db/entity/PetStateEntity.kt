package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * A pet's vitals and what it is doing, as of [updatedAt] — one row per pet.
 *
 * The row is always behind: readers carry it forward to now (`TitanRules.advance`) and never
 * write what they computed. [activity] and [resumeActivity] are stable keys, not validated on
 * restore for the reason `pet.species_id` is not; an unknown one reads as idle.
 *
 * Cascades with its pet.
 */
@Entity(
    tableName = "pet_state",
    foreignKeys = [
        ForeignKey(
            entity = PetEntity::class,
            parentColumns = ["id"],
            childColumns = ["pet_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PetStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "pet_id")
    val petId: Long,
    @ColumnInfo(name = "fuel")
    val fuel: Int,
    @ColumnInfo(name = "spirit")
    val spirit: Int,
    @ColumnInfo(name = "activity")
    val activity: String,
    /** Epoch millis, UTC. */
    @ColumnInfo(name = "activity_started_at")
    val activityStartedAt: Long,
    /** Epoch millis, UTC; null for an activity that ends on a condition rather than a time. */
    @ColumnInfo(name = "activity_ends_at")
    val activityEndsAt: Long?,
    @ColumnInfo(name = "resume_activity")
    val resumeActivity: String?,
    /** The moment this row describes, epoch millis UTC. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
