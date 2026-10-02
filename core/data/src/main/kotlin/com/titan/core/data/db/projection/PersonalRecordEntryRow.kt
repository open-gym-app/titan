package com.titan.core.data.db.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * One row of the records list, as the query assembles it.
 *
 * The record itself is embedded rather than re-declared column by column, so the mapper that
 * already turns a `PersonalRecordEntity` into a domain record is the one used here too — a second
 * hand-written mapping of nine columns is a second place a renamed column has to be noticed.
 *
 * @property previousValue the figure the record beat, in the kind's own unit, or **null** when it
 *   is the first of its kind for that exercise. Looked up per returned row, over the whole table
 *   rather than over the page — see `PersonalRecordDao.getRecordsPage`, where the reason the
 *   lookup is one indexed seek and not a scan is set out.
 * @property exerciseName the exercise's name, or **null** when it has been removed from the
 *   catalogue since. A `LEFT JOIN`, deliberately: an inner one would silently drop records whose
 *   exercise is gone, which is the user's history disappearing because a catalogue row did.
 * @property isRecent whether the record was set in the most recently finished session, as `1` or
 *   `0` — SQLite has no boolean and Room maps the integer for us.
 */
data class PersonalRecordEntryRow(
    @Embedded
    val record: PersonalRecordColumns,
    @ColumnInfo(name = "previous_value")
    val previousValue: Long?,
    @ColumnInfo(name = "exercise_name")
    val exerciseName: String?,
    @ColumnInfo(name = "is_recent")
    val isRecent: Boolean,
)

/**
 * The `personal_record` columns the list needs, named exactly as [PersonalRecordEntity] names
 * them so `@Embedded` picks them straight out of the joined result.
 *
 * A copy of the entity's shape rather than the entity itself: embedding the entity would make
 * Room expect its `id` alongside columns from three other tables, and the ambiguity that creates
 * in a join is resolved by aliasing every column — which is the duplication this avoids, moved
 * into SQL where it is harder to read.
 */
data class PersonalRecordColumns(
    val id: Long,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "workout_set_id")
    val workoutSetId: Long,
    @ColumnInfo(name = "pr_type")
    val prType: String,
    val weight: Long?,
    val reps: Int?,
    val distance: Long?,
    val duration: Long?,
    @ColumnInfo(name = "estimated_1rm")
    val estimated1Rm: Long?,
    @ColumnInfo(name = "achieved_at")
    val achievedAt: Long,
)
