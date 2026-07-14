package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * weight is stored in milligrams (canonical unit, no floating point).
 */
@Entity(
    tableName = "body_weight_log",
    // getAll()/getLatest() both order by created_at DESC; index it to avoid a
    // full-table scan + sort as the log grows.
    indices = [Index("created_at")],
)
data class BodyWeightLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "weight")
    val weight: Long,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
