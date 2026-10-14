package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One gym the user saved, kept entirely on the device (M8-01).
 *
 * ### Equipment tags are one column, not a join table
 *
 * [equipmentTags] holds the list newline-separated (see `GymMapper`). A gym has a handful of
 * tags, nothing queries by one, and a join table would be a second table to migrate, export
 * and keep in dependency order for a list that is only ever read and written whole. Newline
 * rather than comma because a tag is free text that may contain a comma, and the mapper
 * refuses to store one that contains a newline.
 *
 * ### Nothing else is indexed
 *
 * The directory is the user's own list of gyms — dozens at the very most — read whole and
 * ordered by name. An index would cost a write and buy nothing at that size.
 *
 * @property latitude decimal degrees, -90..90, enforced by the domain's `Coordinates`.
 * @property longitude decimal degrees, -180..180.
 * @property equipmentTags the tags in the order entered; empty string for none.
 * @property createdAt epoch milliseconds, UTC.
 */
@Entity(tableName = "gym")
data class GymEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "address")
    val address: String,
    @ColumnInfo(name = "latitude")
    val latitude: Double,
    @ColumnInfo(name = "longitude")
    val longitude: Double,
    @ColumnInfo(name = "equipment_tags")
    val equipmentTags: String,
    @ColumnInfo(name = "user_notes")
    val userNotes: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
