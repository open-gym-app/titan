package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * One tag on one exercise, by the tag's name rather than its id.
 *
 * By name because the names are what the domain agrees with the seed generator about — see
 * `PaceStyle.SPEED_TAG` — while the ids are positions in the generator's list and mean nothing
 * outside one database.
 */
data class ExerciseTagNameRow(
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "tag_name")
    val tagName: String,
)
