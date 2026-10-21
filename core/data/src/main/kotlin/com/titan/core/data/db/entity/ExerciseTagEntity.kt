package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "exercise_tag",
    primaryKeys = ["exercise_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Only tag_id is indexed explicitly: the composite primary key
    // (exercise_id, tag_id) already provides a left-prefix index that covers
    // exercise_id lookups, so a separate exercise_id index would be redundant.
    // tag_id needs its own index for the reverse FK.
    indices = [Index("tag_id")],
)
data class ExerciseTagEntity(
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)
