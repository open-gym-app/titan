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

    /**
     * How many sessions have been closed out over the whole history.
     *
     * The same "finished" the analytics queries use — `is_in_progress = 0` **and** a
     * `completed_at`, the pair `FinishSessionUseCase` writes together — so this figure and the
     * per-week counts below can never disagree about which rows count. A one-shot rather than
     * a `Flow`: its caller is the post-session cascade, which needs the total including the
     * session it has just closed out.
     */
    @Query("SELECT COUNT(*) FROM workout_session WHERE is_in_progress = 0 AND completed_at IS NOT NULL")
    suspend fun countFinished(): Int

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
     * The two line up on more than the boundary: both count only finished sessions and both
     * place a session by the day it was closed out, so every week that has volume in it has
     * a training day behind it and vice versa. [WorkoutSetDao.getVolumeByWeek] carries what
     * that costs.
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
     *
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

    /**
     * Whether the user has ever finished a session — with no window on it, unlike everything
     * else the progression screens read.
     *
     * A period-bounded series cannot answer this. An empty window means "nothing here", and
     * "nothing here" covers both a user who has never trained and one who stopped: the
     * comparison period separates the two only while the lapse is shorter than twice the
     * window, and a six-month break under a twelve-week chart empties that too. This is the
     * bit that stays true however wide the gap, so a screen can show a returning lifter their
     * quiet chart instead of the first-run prompt.
     *
     * `EXISTS` rather than a count: SQLite stops at the first qualifying row, which the
     * `is_in_progress` index takes it straight to. The filter is the one
     * [getTrainingByWeek] and [WorkoutSetDao.getVolumeByWeek] apply, so "has history" means
     * exactly "would appear in those series given a wide enough range".
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM workout_session
            WHERE is_in_progress = 0 AND completed_at IS NOT NULL
        )
        """,
    )
    fun hasFinishedSession(): Flow<Boolean>
}
