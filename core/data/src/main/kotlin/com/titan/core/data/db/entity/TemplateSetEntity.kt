package com.titan.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One set a template plans, under the exercise that plans it.
 *
 * The table that replaced `template_exercise.default_sets/_reps/_weight`. Those three
 * columns could only say "n identical sets", so a template could not hold the warm-up,
 * the working sets and the drop set that make up an ordinary exercise — see
 * [com.titan.core.domain.model.PlannedSet]. A row per set can, and it is the same
 * shape `workout_set` already has, which is what makes applying a template a copy
 * rather than a translation.
 *
 * Canonical storage units, per PERSISTENCE.md: [weight] in milligrams, [duration] in
 * seconds and [distance] in centimetres — the units `workout_set` stores them in, so a
 * planned hold or run is copied into the session without a conversion. There is no
 * `rpe` and no `rest_after` — a plan does not record how hard a set felt, and rest
 * belongs to `template_exercise`.
 *
 * `(template_exercise_id, set_number)` is unique, unlike `workout_set`'s numbering: a
 * template's sets are only ever written wholesale — the whole body is replaced in one
 * transaction — so there is no renumbering pass that would have to park rows out of the
 * way first, and the index is free.
 */
@Entity(
    tableName = "template_set",
    foreignKeys = [
        ForeignKey(
            entity = TemplateExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("template_exercise_id"),
        Index(value = ["template_exercise_id", "set_number"], unique = true),
    ],
)
data class TemplateSetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "template_exercise_id")
    val templateExerciseId: Long,
    /** 1-based and contiguous within one exercise, numbered from list order on the way in. */
    @ColumnInfo(name = "set_number")
    val setNumber: Int,
    /**
     * A [com.titan.core.domain.model.SetType] name. Enum-like text, unconstrained by
     * the schema like every other such column — see PERSISTENCE.md — and read back
     * leniently, so a value from a newer build reads as a normal set rather than making
     * the template unreadable.
     */
    @ColumnInfo(name = "set_type", defaultValue = "NORMAL")
    val setType: String = "NORMAL",
    @ColumnInfo(name = "reps")
    val reps: Int? = null,
    /** Milligrams, per [WorkoutSetEntity.weight]. */
    @ColumnInfo(name = "weight")
    val weight: Long? = null,
    /** Seconds, per [WorkoutSetEntity.duration]. What a hold or a timed effort is planned in. */
    @ColumnInfo(name = "duration")
    val duration: Long? = null,
    /** Centimetres, per [WorkoutSetEntity.distance]. */
    @ColumnInfo(name = "distance")
    val distance: Long? = null,
)
