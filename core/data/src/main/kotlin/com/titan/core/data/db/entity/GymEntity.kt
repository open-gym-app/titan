package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One gym in the user's own directory. See `Gym`.
 *
 * Coordinates are integer microdegrees, not REAL, so the table holds no floating point and
 * round-trips through an export exactly — see docs/PERSISTENCE.md. [equipmentTags] is the
 * tags one per line (`''` for none); `GymValidator` refuses a tag that contains a line break,
 * and an importer writing this table directly has to check it itself.
 */
@Entity(tableName = "gym")
data class GymEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "address")
    val address: String,
    @ColumnInfo(name = "latitude_micro")
    val latitudeMicro: Int,
    @ColumnInfo(name = "longitude_micro")
    val longitudeMicro: Int,
    @ColumnInfo(name = "equipment_tags")
    val equipmentTags: String,
    @ColumnInfo(name = "user_notes")
    val userNotes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
