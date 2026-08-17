package com.opengym.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_template")
data class WorkoutTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    /**
     * A [com.opengym.core.domain.model.TemplateCategory] name, or NULL when the
     * template has not been given one. Enum-like text, unconstrained by the schema
     * like every other such column — see PERSISTENCE.md.
     *
     * Unindexed on purpose: the only read that would use it is a filter over a list a
     * user has a few dozen rows of, which SQLite scans faster than it would descend an
     * index it has to keep up to date on every template write.
     */
    @ColumnInfo(name = "category")
    val category: String? = null,
    @ColumnInfo(name = "icon")
    val icon: String? = null,
)
