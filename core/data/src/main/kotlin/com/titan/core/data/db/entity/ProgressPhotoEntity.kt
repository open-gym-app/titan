package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One progress photo. See `ProgressPhoto` for why it is linked to a date rather than to a body
 * measurement row, and why it is not part of an export.
 *
 * [fileName] names the image inside the app's private photo directory and nothing more — never a
 * path, so a move of the files directory cannot strand a row — and is unique so two rows can never
 * claim one image.
 */
@Entity(
    tableName = "progress_photo",
    // The one read orders by taken_at DESC over a table that grows for the life of the install.
    indices = [Index("taken_at"), Index(value = ["file_name"], unique = true)],
)
data class ProgressPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "taken_at")
    val takenAt: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
