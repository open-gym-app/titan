package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One thing the user's training has unlocked for the titan — a place now, gear later — kept once
 * earned. Keyed by the unlockable's stable key, so writing it again is a no-op; a key this build
 * does not know survives a restore, for `achievement`'s reason.
 */
@Entity(tableName = "pet_unlock")
data class PetUnlockEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    /** Epoch millis, UTC: when it was first earned. */
    @ColumnInfo(name = "unlocked_at")
    val unlockedAt: Long,
)
