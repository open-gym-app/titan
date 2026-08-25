package com.opengym.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.WorkoutSessionEntity
import com.opengym.core.data.db.projection.WeeklyTrainingRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: WorkoutSessionEntity): Long

    @Update
    suspend fun update(session: WorkoutSessionEntity)

    @Query("DELETE FROM workout_session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM workout_session ORDER BY created_at DESC")
    fun getAll(): Flow<List<WorkoutSessionEntity>>

    /**
     * Paginated variant of [getAll] for the session-history screen, which grows
     * unbounded over the app's lifetime. Kept alongside [getAll] so callers that
     * need the full list (charts, exports) keep the Flow<List> option.
     */
    @Query("SELECT * FROM workout_session ORDER BY created_at DESC")
    fun getAllPaginated(): PagingSource<Int, WorkoutSessionEntity>

    @Query("SELECT * FROM workout_session WHERE id = :id")
    fun getById(id: Long): Flow<WorkoutSessionEntity?>

    @Query(
        """
        SELECT * FROM workout_session
        WHERE is_in_progress = 1
        ORDER BY last_active_at DESC
        LIMIT 1
        """,
    )
    fun getActive(): Flow<WorkoutSessionEntity?>

    /**
     * How often the user trained, per week, over the half-open day range
     * `[startEpochDay, endEpochDayExclusive)` — one row per week that holds at least one
     * finished session. The counting runs in SQLite, so the result grows with the number
     * of *weeks* rather than the number of sessions.
     *
     * Weeks start on Monday, UTC, cut with the same arithmetic
     * [WorkoutSetDao.getVolumeByWeek] uses so the volume and consistency series line up
     * bucket for bucket; see that method for what the `+ 3` is doing.
     *
     * `COUNT(DISTINCT day)` rather than `COUNT(*)`: two sessions finished on the same day
     * are one day trained, which is what a consistency figure means and what
     * `ConsistencyUseCase` reads. The raw count comes back beside it for the surfaces
     * that want to say how many sessions those days held.
     *
     * Only sessions that were **finished** count — `is_in_progress = 0` and a
     * `completed_at` to bucket by, which `FinishSessionUseCase` writes together. A
     * session still being logged has not happened yet, and a discarded one is deleted
     * rather than flagged, so there is no third state to exclude.
     */
    @Query(
        """
        SELECT
            (s.completed_at / 86400000) - (((s.completed_at / 86400000) + 3) % 7) AS week_start,
            COUNT(DISTINCT s.completed_at / 86400000) AS training_days,
            COUNT(*) AS session_count
        FROM workout_session s
        WHERE s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
            AND s.completed_at >= :startEpochDay * 86400000
            AND s.completed_at < :endEpochDayExclusive * 86400000
        GROUP BY week_start
        ORDER BY week_start ASC
        """,
    )
    fun getTrainingByWeek(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<List<WeeklyTrainingRow>>
}
