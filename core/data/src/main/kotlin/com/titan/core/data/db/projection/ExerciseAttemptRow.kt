package com.titan.core.data.db.projection

import androidx.room.ColumnInfo

/**
 * One session's worth of one exercise, produced by
 * [com.titan.core.data.db.dao.WorkoutSetDao.getAttemptsByExercise].
 *
 * The per-exercise drill-down's row: it draws one of these as a bar on the volume chart, a
 * point on the one-rep-max line and a line of the recent-attempts table, so the three cannot
 * disagree about what a session held. Rolled up in SQL, like every other aggregate in that
 * DAO — a year of benching is fifty of these rather than the three hundred sets behind them.
 *
 * @property sessionId the session the exercise was performed in. One row per session, even
 *   where the workout held the exercise in two slots: the two are one attempt at the lift,
 *   which is the same grouping the PR engine applies (see `PrDetectionUseCase`).
 * @property finishedAt when the **session** was closed out, epoch millis. What the row is
 *   dated by and ordered on, matching every other progression aggregate — see
 *   [com.titan.core.data.db.dao.WorkoutSetDao.getVolumeByWeek].
 * @property setCount completed working sets. Warm-ups excluded, per the DAO query.
 * @property totalVolume `weight × reps` over those sets, in milligram-reps.
 * @property firstSetAt the earliest set stamp of the attempt, epoch millis. What tells a
 *   caller whether a record achieved at some instant was set *in this attempt* — a record
 *   carries the set's own stamp rather than the session's, so no other column answers it.
 * @property topSetWeight the heaviest set's weight in milligrams, or null for an attempt of
 *   bodyweight or timed work.
 * @property topSetReps that same set's reps, or null where it recorded none.
 */
data class ExerciseAttemptRow(
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long,
    @ColumnInfo(name = "set_count")
    val setCount: Int,
    @ColumnInfo(name = "total_volume")
    val totalVolume: Long,
    @ColumnInfo(name = "timed_seconds")
    val timedSeconds: Long,
    @ColumnInfo(name = "total_distance")
    val totalDistance: Long,
    @ColumnInfo(name = "first_set_at")
    val firstSetAt: Long,
    @ColumnInfo(name = "top_weight")
    val topSetWeight: Long?,
    @ColumnInfo(name = "top_reps")
    val topSetReps: Int?,
    @ColumnInfo(name = "top_duration")
    val topSetDuration: Long?,
    @ColumnInfo(name = "top_distance")
    val topSetDistance: Long?,
)
