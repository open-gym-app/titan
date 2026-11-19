package com.titan.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.data.db.projection.SessionDisciplineRow
import com.titan.core.data.db.projection.WeeklyTrainingRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: WorkoutSessionEntity): Long

    /**
     * Inserts sessions read from Health Connect, skipping any whose `health_connect_id` is
     * already stored. Returns one rowid per session, [NOT_INSERTED] for each one skipped.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImported(sessions: List<WorkoutSessionEntity>): List<Long>

    /** The session [id], if it is finished — the same "finished" [countFinishedBy] counts. */
    @Query("SELECT * FROM workout_session WHERE id = :id AND is_in_progress = 0 AND completed_at IS NOT NULL")
    suspend fun getFinished(id: Long): WorkoutSessionEntity?

    /**
     * Every finished session that started in `[startedFrom, startedUntil)`, oldest first.
     *
     * A range scan on the `created_at` index — the month the Health Connect import compares
     * against is a few dozen rows of a table that may hold thousands.
     */
    @Query(
        """
        SELECT * FROM workout_session
        WHERE created_at >= :startedFrom AND created_at < :startedUntil
            AND is_in_progress = 0 AND completed_at IS NOT NULL
        ORDER BY created_at
        """,
    )
    suspend fun getFinishedStartedBetween(
        startedFrom: Long,
        startedUntil: Long,
    ): List<WorkoutSessionEntity>

    /**
     * The discipline of every exercise with at least one completed set, in each of [sessionIds].
     *
     * One row per `session_exercise`, so an exercise done twice in one session appears twice —
     * which changes nothing for `WorkoutActivity.of`, whose answer depends on which disciplines
     * appear and not on how often. An exercise whose sets were all left unticked is not here: it
     * was planned and not done, and a session is what was done in it.
     */
    @Query(
        """
        SELECT se.session_id AS session_id, e.discipline AS discipline
        FROM session_exercise se
        JOIN exercise e ON e.id = se.exercise_id
        WHERE se.session_id IN (:sessionIds)
            AND EXISTS (
                SELECT 1 FROM workout_set ws
                WHERE ws.session_exercise_id = se.id AND ws.is_completed = 1
            )
        """,
    )
    suspend fun getPerformedDisciplines(sessionIds: List<Long>): List<SessionDisciplineRow>

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
     * How many sessions had been closed out by [atMillis], inclusive.
     *
     * The same "finished" the analytics queries use — `is_in_progress = 0` **and** a
     * `completed_at`, the pair `FinishSessionUseCase` writes together — so this figure and the
     * per-week counts below can never disagree about which rows count. A one-shot rather than
     * a `Flow`: its caller is the post-session cascade, which needs the total including the
     * session it has just closed out. `completed_at <= :atMillis` already excludes a NULL, so
     * the bound is the whole of the "has a `completed_at`" half.
     *
     * Sessions imported from Health Connect are left out, and that is the one way this differs
     * from the charts. The figure is what the session-count achievements are judged against, and
     * an import from there earns nothing — see `WorkoutSession.healthConnectId`. Counting it here
     * would pay for it anyway, at the next workout's cascade.
     */
    @Query(
        """
        SELECT COUNT(*) FROM workout_session
        WHERE is_in_progress = 0 AND completed_at <= :atMillis AND health_connect_id IS NULL
        """,
    )
    suspend fun countFinishedBy(atMillis: Long): Int

    /**
     * The session being logged — the in-progress row touched most recently — or null.
     *
     * Only a row with a positive id, which is every id `AUTOINCREMENT` assigns. A row numbered
     * at or below zero can only come from a hand-edited backup, and the workout draft refuses
     * to be built around one, so offering it for resume would throw on every launch. It is
     * left where it is and never resumed.
     */
    @Query(
        """
        SELECT * FROM workout_session
        WHERE is_in_progress = 1 AND id > 0
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
     * bucket for bucket; see that method for what the `+ 3` is doing, and for why a session
     * finished before 1970 falls in no bucket.
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
            AND s.completed_at >= 0
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

    /**
     * How long the user spent training over the half-open day range
     * `[startEpochDay, endEpochDayExclusive)`, in milliseconds.
     *
     * A session's length is `created_at` to `completed_at` and needs no set at all — see
     * [com.titan.core.domain.model.SessionTotals], which deliberately has no duration in it
     * for that reason. So this is a sum over the session rows alone rather than a fourth
     * aggregate over `workout_set`.
     *
     * The same filter and the same bucketing as [getTrainingByWeek]: finished sessions, placed
     * by the day they were closed out. A workout that opened before the window and finished
     * inside it therefore contributes its **whole** length to the window, which is the only
     * answer consistent with the volume and consistency series counting it whole too — a
     * session is one event, and splitting one across two periods would put half a workout in
     * a month the user did not train in.
     *
     * `MAX(..., 0)` per row, not over the sum: a device whose clock went backwards mid-workout
     * writes a `completed_at` before its `created_at`, and one such session would otherwise
     * subtract real training time from the total. Clamped here rather than at insert because
     * the row is a faithful record of what the clock said.
     *
     * `CAST(TOTAL(…) AS INTEGER)` rather than `SUM`, for the reason `WorkoutSetDao` gives for
     * its own sums: a restored row can hold timestamps far enough apart that an integer `SUM`
     * raises `integer overflow`, where `TOTAL` cannot. It is also zero over no rows, and a
     * window with no training in it is zero milliseconds rather than an absent answer.
     */
    @Query(
        """
        SELECT CAST(TOTAL(MAX(s.completed_at - s.created_at, 0)) AS INTEGER)
        FROM workout_session s
        WHERE s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
            AND s.completed_at >= :startEpochDay * 86400000
            AND s.completed_at < :endEpochDayExclusive * 86400000
        """,
    )
    fun getTrainingMillis(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<Long>

    /**
     * How many sessions the user finished over the half-open day range
     * `[startEpochDay, endEpochDayExclusive)`.
     *
     * A scalar over an arbitrary range, like [getTrainingMillis] beside it, rather than a
     * series to be summed. The quest board asks for a *month*, and a month is not a whole
     * number of the Monday weeks [getTrainingByWeek] buckets by — so summing that series would
     * be reading a charting API off its grain to get an answer this states directly in one row.
     *
     * `COUNT(*)`, not `COUNT(DISTINCT day)`: a quest to train five times is met by five
     * workouts however they fall across the days, which is the opposite of the reading
     * [getTrainingByWeek] takes for consistency. The two figures differ for anyone who trains
     * twice in a day, and both are correct for what they are asked.
     *
     * The same filter as every other aggregate here, except that a session imported from Health
     * Connect is not counted: a quest pays XP, and an import earns nothing. It still counts towards
     * the charts — it was a day trained — which is why that exception is here and nowhere else in
     * this file. See [countFinishedBy], which makes it for the achievements.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM workout_session s
        WHERE s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
            AND s.health_connect_id IS NULL
            AND s.completed_at >= :startEpochDay * 86400000
            AND s.completed_at < :endEpochDayExclusive * 86400000
        """,
    )
    fun countFinishedIn(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<Int>

    /**
     * The day the user's first finished session was closed out on, or **null** when there is
     * none.
     *
     * What the stats hub's `ALL` timeframe opens on. A period has to have a start, and the
     * alternatives are both wrong: a fixed date far enough back is a window that grows for the
     * life of the install and quietly widens every aggregate's scan, and "the last ten years"
     * is a number somebody picked. `MIN` over an indexed column is a single seek.
     *
     * The same filter as [getTrainingByWeek], so `ALL` covers exactly the sessions the series
     * can return — never a leading run of empty weeks before the user had started. That
     * includes its `completed_at >= 0`: a session finished before 1970 falls in no bucket, so
     * opening the period on its day would only add empty weeks in front of the real ones.
     */
    @Query(
        """
        SELECT MIN(completed_at / 86400000)
        FROM workout_session
        WHERE is_in_progress = 0 AND completed_at IS NOT NULL AND completed_at >= 0
        """,
    )
    fun getFirstTrainingDay(): Flow<Long?>
}
