package com.opengym.core.data.db.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded

/**
 * One [ExerciseMuscleGroupLinkRow], plus the exercise it belongs to, produced by
 * [com.opengym.core.data.db.dao.ExerciseDao.getAllMuscleGroupLinks].
 *
 * The per-exercise query already knows which exercise it asked about, so its projection does
 * not carry the id. The catalogue-wide one returns every link in the database in a single
 * result set, and without the id there would be no way to tell whose link is whose.
 *
 * [@Embedded][Embedded] rather than five repeated fields so both queries map through the same
 * `MuscleGroupLinkMapper.toDomain(ExerciseMuscleGroupLinkRow)` — a second copy of the same
 * five columns is a second thing to keep in step with the join.
 */
data class CatalogueMuscleGroupLinkRow(
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @Embedded
    val link: ExerciseMuscleGroupLinkRow,
)
