package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One dated set of body measurements. See `BodyMeasurement` for why weight and the
 * circumferences share a table and why every column is nullable.
 *
 * Canonical integer units, no floating point: weight in milligrams, circumferences in
 * millimetres, body fat in tenths of a percent. Lean mass is **derived** from weight and body
 * fat and has no column — see `BodyMeasurement.leanMass`.
 */
@Entity(
    tableName = "body_measurement",
    // getAll()/getLatest() both order by created_at DESC; index it to avoid a
    // full-table scan + sort as the log grows.
    indices = [Index("created_at")],
)
data class BodyMeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "weight")
    val weight: Long? = null,
    @ColumnInfo(name = "chest")
    val chest: Int? = null,
    @ColumnInfo(name = "waist")
    val waist: Int? = null,
    @ColumnInfo(name = "arm")
    val arm: Int? = null,
    @ColumnInfo(name = "thigh")
    val thigh: Int? = null,
    @ColumnInfo(name = "body_fat")
    val bodyFat: Int? = null,
    @ColumnInfo(name = "notes")
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
