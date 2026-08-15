package com.opengym.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.WorkoutSetEntity
import com.opengym.core.data.db.projection.MuscleVolumeRow
import com.opengym.core.data.db.projection.SessionTotalsRow
import com.opengym.core.data.db.projection.WeeklyVolumeRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSetDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(set: WorkoutSetEntity): Long

    @Update
    suspend fun update(set: WorkoutSetEntity)

    @Query("DELETE FROM workout_set WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM workout_set WHERE session_exercise_id = :sessionExerciseId ORDER BY set_number ASC")
    fun getBySessionExercise(sessionExerciseId: Long): Flow<List<WorkoutSetEntity>>

    /**
     * Every set logged against [sessionId], across all of its exercises, in the
     * order the session presents them.
     *
     * One join rather than a [getBySessionExercise] per exercise: a session summary
     * or an export wants the whole session, and walking it exercise by exercise is
     * an N+1 whose fan-out grows with how much the user actually did. The rows carry
     * `session_exercise_id`, so a caller that wants them grouped by exercise groups
     * what it already has.
     */
    @Query(
        """
        SELECT ws.* FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        WHERE se.session_id = :sessionId
        ORDER BY se.position ASC, ws.set_number ASC
        """,
    )
    fun getBySession(sessionId: Long): Flow<List<WorkoutSetEntity>>

    /**
     * Every session's set count and volume, one row each.
     *
     * The history list's read, and the reason it is an aggregate rather than a
     * [getBySession] per row: a list of sessions has no upper bound, and a query per row
     * would put an observer on the database for each of them. Here the `SUM` and `COUNT`
     * run in SQLite, so what crosses the boundary is two numbers per session however many
     * sets are behind them.
     *
     * Only completed sets count, matching the filter every other aggregate in this file
     * applies and the one [com.opengym.core.domain.stats.SessionStatsCalculator] applies in
     * memory — a session row can hold placeholder sets for planned-but-unperformed work
     * (see PERSISTENCE.md), and counting those would inflate a figure the user reads beside
     * the recap that does not count them.
     *
     * Sets with no `weight` or no `reps` (cardio, timed holds) contribute zero volume rather
     * than dropping their session from the result: a session of nothing but running has a
     * set count worth showing and a volume of zero, which is the truth about it.
     *
     * A session with no completed sets produces no row at all. `GROUP BY` has nothing to
     * group for it, and the alternative — an outer join from `workout_session` — would be a
     * scan of every session to manufacture zeroes the caller already knows how to read as
     * an absence.
     */
    @Query(
        """
        SELECT
            se.session_id AS session_id,
            COUNT(*) AS total_sets,
            SUM(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS total_volume
        FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        WHERE ws.is_completed = 1
        GROUP BY se.session_id
        """,
    )
    fun getTotalsBySession(): Flow<List<SessionTotalsRow>>

    @Query(
        """
        SELECT ws.* FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        WHERE se.exercise_id = :exerciseId AND ws.is_completed = 1
        ORDER BY ws.completed_at DESC
        """,
    )
    fun getHistoryByExercise(exerciseId: Long): Flow<List<WorkoutSetEntity>>

    /**
     * Paginated variant of [getHistoryByExercise]. This is the heaviest history
     * query (every completed set for one exercise, all-time) and grows unbounded,
     * so the per-exercise history screen should page through it.
     */
    @Query(
        """
        SELECT ws.* FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        WHERE se.exercise_id = :exerciseId AND ws.is_completed = 1
        ORDER BY ws.completed_at DESC
        """,
    )
    fun getHistoryByExercisePaginated(exerciseId: Long): PagingSource<Int, WorkoutSetEntity>

    /**
     * Weekly training volume from [sinceEpochDay] onwards, one row per week that
     * has at least one completed set. The chart on the progression screen reads
     * this instead of loading the underlying sets: the `SUM` runs in SQLite, so
     * the result grows with the number of *weeks*, not the number of sets.
     *
     * Weeks start on Monday, UTC. `completed_at` holds epoch milliseconds, so
     * `completed_at / 86400000` is the epoch day; epoch day 0 was a Thursday,
     * hence the `+ 3` that shifts Monday onto a multiple of 7 before rounding
     * the day down to the start of its week.
     *
     * Sets with no `weight` or no `reps` (cardio, holds) count as zero volume
     * rather than dropping their week from the series.
     */
    @Query(
        """
        SELECT
            (ws.completed_at / 86400000) - (((ws.completed_at / 86400000) + 3) % 7) AS week_start,
            SUM(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS total_volume
        FROM workout_set ws
        WHERE ws.is_completed = 1 AND ws.completed_at >= :sinceEpochDay * 86400000
        GROUP BY week_start
        ORDER BY week_start ASC
        """,
    )
    fun getVolumeByWeek(sinceEpochDay: Long): Flow<List<WeeklyVolumeRow>>

    /**
     * Volume per muscle group over the half-open day range
     * `[startEpochDay, endEpochDayExclusive)`, aggregated in SQL so a wide range
     * returns one row per muscle group instead of every set in it.
     *
     * The join fans a set out across `exercise_muscle_group`, so an exercise
     * with one primary and two secondary muscles credits all three; the
     * `GROUP BY` then collapses each group back to a single row, which is what
     * keeps a multi-secondary exercise from counting a set twice against the
     * same muscle. See [MuscleVolumeRow] for how the two sums differ.
     */
    @Query(
        """
        SELECT
            mg.id AS muscle_group_id,
            mg.name AS muscle_group_name,
            SUM(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS total_volume,
            SUM(
                CASE WHEN emg.is_primary = 1
                    THEN COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)
                    ELSE (COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) / 2
                END
            ) AS weighted_volume
        FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        INNER JOIN exercise_muscle_group emg ON emg.exercise_id = se.exercise_id
        INNER JOIN muscle_group mg ON mg.id = emg.muscle_group_id
        WHERE ws.is_completed = 1
            AND ws.completed_at >= :startEpochDay * 86400000
            AND ws.completed_at < :endEpochDayExclusive * 86400000
        GROUP BY mg.id, mg.name
        ORDER BY total_volume DESC, mg.name ASC
        """,
    )
    fun getVolumeByMuscleGroup(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<List<MuscleVolumeRow>>
}
