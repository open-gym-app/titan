package com.opengym.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * One exercise the user has actually logged, produced by
 * [com.opengym.core.data.db.dao.WorkoutSetDao.getTrainedExercises].
 *
 * What the per-exercise drill-down is picked from. The catalogue is 873 rows and almost none
 * of them are the user's — a chooser built from `ExerciseRepository.getAll()` would be a
 * search problem, where this is a short list ordered by what they trained last.
 *
 * @property exerciseId the catalogue row.
 * @property name its name, carried along so the chooser is one read rather than a join done
 *   in Kotlin against the whole catalogue.
 * @property setCount completed working sets, all-time. The same figure the drill-down's
 *   `TOTAL SETS` card shows, from the same filter — see
 *   [com.opengym.core.data.db.dao.WorkoutSetDao.countSetsByExercise].
 * @property lastPerformedAt when the most recent session holding it was closed out, epoch
 *   millis. What the list is ordered on: the lift somebody wants to look at is nearly always
 *   the one they did this week.
 */
data class TrainedExerciseRow(
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "set_count")
    val setCount: Int,
    @ColumnInfo(name = "last_performed_at")
    val lastPerformedAt: Long,
)
