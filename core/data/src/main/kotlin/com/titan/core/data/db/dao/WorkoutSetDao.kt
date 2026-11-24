package com.titan.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.titan.core.data.db.entity.WorkoutSetEntity
import com.titan.core.data.db.projection.ExerciseAttemptRow
import com.titan.core.data.db.projection.MuscleVolumeRow
import com.titan.core.data.db.projection.PreviousSetRow
import com.titan.core.data.db.projection.SessionTotalsRow
import com.titan.core.data.db.projection.TrainedExerciseRow
import com.titan.core.data.db.projection.WeeklyVolumeRow
import kotlinx.coroutines.flow.Flow

/**
 * The `workout_set` table, and the aggregates every history and stats screen reads off it.
 *
 * ### Every sum is `CAST(TOTAL(…) AS INTEGER)`, never `SUM`
 *
 * SQLite's `SUM` over integers raises `integer overflow` once the running total leaves a 64-bit
 * integer, and that error fails the whole query. `SetValidator` keeps every set the app writes
 * far from that, but nothing in the schema holds its bounds, so a restored backup can hold a
 * weight or a duration that overflows the first sum it reaches. The History list, the Stats
 * screen and the dashboard would then throw at launch. `TOTAL` sums in floating point and cannot
 * overflow. The cast turns the result back into the integer every column is read as, and holds
 * anything past the `Long` bounds at those bounds. Below 2^53 each partial sum is an integer a
 * double holds exactly, so an honest history sums to exactly what `SUM` would have given: 2^53
 * milligram-reps is nine million tonne-reps.
 */
@Dao
interface WorkoutSetDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(set: WorkoutSetEntity): Long

    /**
     * Several sets at once, in one transaction — Room's own for a multi-row `@Insert`, the
     * same guarantee [updateAll] leans on.
     *
     * The ids come back in the order given, which is what lets a caller that just laid out a
     * plan know which row each planned set became without reading the table back.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(sets: List<WorkoutSetEntity>): List<Long>

    @Update
    suspend fun update(set: WorkoutSetEntity)

    /**
     * Rewrites several sets in one transaction — a renumbering, in practice.
     *
     * Room runs a multi-row `@Update` inside a transaction, which is the whole of what this
     * needs and the reason it is not the parked, two-phase write `SessionExerciseDao` does for a
     * reorder. The difference is the schema: `session_exercise` is unique on
     * `(session_id, position)`, so two rows swapping places have to pass through a position
     * nothing can be holding, while `workout_set` carries no unique index on
     * `(session_exercise_id, set_number)` — the numbers are kept contiguous by the use cases —
     * so the rows can go straight to the numbers they belong on.
     *
     * What the transaction is for is the observer: a table halfway through closing the gap left
     * by a deleted set is numbered `1, 2, 4`, and no screen should ever read that.
     */
    @Update
    suspend fun updateAll(sets: List<WorkoutSetEntity>)

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
     * The last set table performed for each exercise [sessionId] holds, from any session but
     * that one — the `PREV` column of the active workout, for the whole screen, in one read.
     *
     * ### One query, ten exercises
     *
     * The exercises are a sub-select rather than a parameter, so a workout of ten reads the
     * same way a workout of one does. The alternative — the caller holding the layout and
     * asking per exercise — is the fan-out [getBySession] exists to avoid, made worse: each of
     * those lookups is a `Flow`, so a ten-exercise session would hold eleven observers open on
     * `workout_set` for the length of a workout.
     *
     * ### How "the last one" is chosen
     *
     * Per `session_exercise` row, not per session. `last_slot` picks the newest slot for each
     * exercise — by the session's `created_at`, then by position within it, then by row id so
     * two sessions stamped in the same millisecond still resolve to one answer — among the
     * slots that have a qualifying set, and the outer query returns that slot's sets. Choosing
     * a *session* instead would merge the two slots of a workout that held the exercise twice
     * into one table numbered `1, 2, 1, 2`.
     *
     * The session's own rows are excluded inside `last_slot`, which is the only place they
     * could be chosen from. Without it every exercise's "last time" would be the set the user
     * logged a minute ago on the row above.
     *
     * ### Why the slot is chosen from the exercise list, not from the sets
     *
     * `last_slot` is driven by *today's* distinct exercises, so the sub-select that sorts an
     * exercise's slot history runs once per exercise on the screen — ten times, whatever the
     * user's history holds. Correlating it on the historical rows instead reads the same and
     * returns the same rows, but SQLite then re-evaluates it once per candidate
     * `session_exercise` row, which is once per *past* performance: the work grows with the
     * square of a user's history rather than with the size of today's workout. Since this
     * query observes `workout_set` it re-runs on every set the user ticks off, so that cost
     * lands on the one path that has to stay responsive mid-workout. Measured against a
     * five-year history the difference was ~200ms per read against ~1ms.
     *
     * A slot id of `NULL` — an exercise never performed before — joins to no set, which is
     * the absence the caller reads as "first time".
     *
     * ### Which sets count
     *
     * `is_completed = 1`, matching every other aggregate here, and `set_type <> 'WARMUP'` — the
     * decision is stated in full on [com.titan.core.domain.repository.SessionRepository.getPreviousSets].
     * The same filter appears in the `EXISTS` guard, so a slot whose only sets are warm-ups is
     * not picked as "the last time" and then returned empty.
     *
     * Ordered by exercise and then by `set_number`, so the caller groups a list that is already
     * in the order the sets were performed.
     */
    @Query(
        """
        WITH last_slot AS (
            SELECT
                today.exercise_id AS exercise_id,
                (
                    SELECT prev.id
                    FROM session_exercise prev
                    INNER JOIN workout_session s ON s.id = prev.session_id
                    WHERE prev.exercise_id = today.exercise_id
                        AND prev.session_id <> :sessionId
                        AND EXISTS (
                            SELECT 1 FROM workout_set pws
                            WHERE pws.session_exercise_id = prev.id
                                AND pws.is_completed = 1
                                AND pws.set_type <> 'WARMUP'
                        )
                    ORDER BY s.created_at DESC, prev.position DESC, prev.id DESC
                    LIMIT 1
                ) AS slot_id
            FROM (
                SELECT DISTINCT exercise_id FROM session_exercise WHERE session_id = :sessionId
            ) today
        )
        SELECT ws.*, last_slot.exercise_id AS exercise_id
        FROM last_slot
        INNER JOIN workout_set ws ON ws.session_exercise_id = last_slot.slot_id
        WHERE ws.is_completed = 1
            AND ws.set_type <> 'WARMUP'
        ORDER BY last_slot.exercise_id ASC, ws.set_number ASC
        """,
    )
    fun getPreviousSetsForSession(sessionId: Long): Flow<List<PreviousSetRow>>

    /**
     * The working sets of the [limit] most recent slots that performed [exerciseId], newest
     * slot first and each slot's sets in `set_number` order — excluding [sessionId]'s own, or
     * excluding nothing when [sessionId] is null.
     *
     * The next-load engine's history read (#M7-15), and the per-exercise twin of
     * [getPreviousSetsForSession]: the same choice of slot — by the session's `created_at`,
     * then position, then row id — and the same idea of a qualifying set, completed and not a
     * warm-up, so that the engine's "last time" is the `PREV` column's "last time". The one
     * difference is depth: this reaches [limit] slots back where that reaches one, because the
     * rule it feeds looks at the performance before the last.
     *
     * The `EXISTS` guard is inside the `IN` sub-select for the reason it is inside `last_slot`
     * there: a slot whose only sets were warm-ups must not take one of the [limit] places and
     * then contribute nothing, which would read as a shorter history than the user has.
     *
     * `LIMIT` inside an `IN` sub-select is SQLite's own extension and is what keeps this a read
     * of a handful of rows on any history: the outer join only ever sees the slots chosen.
     * The outer `ORDER BY` restates the sub-select's, since a sub-select's order does not
     * survive the `IN` — SQL sets are unordered — and the repository groups by encounter order.
     */
    @Query(
        """
        SELECT ws.*
        FROM workout_set ws
        INNER JOIN session_exercise se ON se.id = ws.session_exercise_id
        INNER JOIN workout_session s ON s.id = se.session_id
        WHERE se.id IN (
            SELECT prev.id
            FROM session_exercise prev
            INNER JOIN workout_session ps ON ps.id = prev.session_id
            WHERE prev.exercise_id = :exerciseId
                AND (:sessionId IS NULL OR prev.session_id <> :sessionId)
                AND EXISTS (
                    SELECT 1 FROM workout_set pws
                    WHERE pws.session_exercise_id = prev.id
                        AND pws.is_completed = 1
                        AND pws.set_type <> 'WARMUP'
                )
            ORDER BY ps.created_at DESC, prev.position DESC, prev.id DESC
            LIMIT :limit
        )
            AND ws.is_completed = 1
            AND ws.set_type <> 'WARMUP'
        ORDER BY s.created_at DESC, se.position DESC, se.id DESC, ws.set_number ASC
        """,
    )
    fun getRecentSetsByExercise(
        exerciseId: Long,
        sessionId: Long?,
        limit: Int,
    ): Flow<List<WorkoutSetEntity>>

    /**
     * Every session's set count and volume, one row each.
     *
     * The history list's read, and the reason it is an aggregate rather than a
     * [getBySession] per row: a list of sessions has no upper bound, and a query per row
     * would put an observer on the database for each of them. Here the sums and the `COUNT`
     * run in SQLite, so what crosses the boundary is two numbers per session however many
     * sets are behind them.
     *
     * Only completed sets count, matching the filter every other aggregate in this file
     * applies and the one [com.titan.core.domain.stats.SessionStatsCalculator] applies in
     * memory — a session row can hold placeholder sets for planned-but-unperformed work
     * (see PERSISTENCE.md), and counting those would inflate a figure the user reads beside
     * the recap that does not count them.
     *
     * Sets with no `weight` or no `reps` (cardio, timed holds) contribute zero volume rather
     * than dropping their session from the result: a session of nothing but running has a
     * set count worth showing and a volume of zero, which is the truth about it. What such a
     * session *did* is the other two sums — the time its sets were held or run for, and the
     * ground they covered — so a history row for a run has a figure to quote.
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
            CAST(TOTAL(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS INTEGER) AS total_volume,
            CAST(TOTAL(COALESCE(ws.duration, 0)) AS INTEGER) AS timed_seconds,
            CAST(TOTAL(COALESCE(ws.distance, 0)) AS INTEGER) AS total_distance
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
     * Weekly training volume over the half-open day range
     * `[startEpochDay, endEpochDayExclusive)`, one row per week that has at least
     * one completed set in it. The chart on the progression screen reads this
     * instead of loading the underlying sets: the sum runs in SQLite, so the
     * result grows with the number of *weeks*, not the number of sets.
     *
     * ### A week is a set of finished sessions
     *
     * A set counts when it is completed **and** the session holding it was closed out —
     * `is_in_progress = 0` with a `completed_at`, the pair `FinishSessionUseCase` writes,
     * exactly the filter [WorkoutSessionDao.getTrainingByWeek] applies. The session is also
     * what the bucket is cut on: every set of a workout lands in the week that workout was
     * finished in, whatever its own stamp says.
     *
     * Both halves of that are what put this series and the consistency series on one axis,
     * and neither is free:
     *
     *  - Volume from a session still being logged is **not** charted until the user finishes
     *    it, so the current week's bar fills in at the end of a workout rather than set by
     *    set. The alternative charts work that has no training day behind it, and leaves an
     *    abandoned session's volume in the chart for good.
     *  - A session that starts 23:30 Sunday UTC and ends after midnight is wholly in the
     *    second week — its volume and its training day together — rather than split across
     *    the boundary.
     *
     * A session's *own* recap does not go through here: [getTotalsBySession] is per session
     * and unfiltered by session state, so a workout in progress still totals live on the
     * screen the user is looking at.
     *
     * ### Where the week boundary comes from
     *
     * Weeks start on Monday, UTC. `completed_at` holds epoch milliseconds, so
     * `completed_at / 86400000` is the epoch day; epoch day 0 was a Thursday,
     * hence the `+ 3` that shifts Monday onto a multiple of 7 before rounding
     * the day down to the start of its week. The same arithmetic appears in
     * [WorkoutSessionDao.getTrainingByWeek] and in `:core:domain`'s `TrainingWeek`,
     * and `TrainingWeekParityTest` is what holds the three to one answer.
     *
     * The arithmetic holds only for a `completed_at` at or after 1970: SQLite's `/` and `%`
     * truncate towards zero, so an earlier day would land in a bucket that does not start on a
     * Monday. Such a session is therefore left out of every bucket by `completed_at >= 0`. The
     * import refuses a timestamp before 1970, so only a row written some other way is affected.
     *
     * Bounded at both ends, like [getVolumeByMuscleGroup], because the progression
     * screen's "vs previous period" needs a window that stops: an open-ended query
     * would fold everything after the period into its last bucket. A caller that
     * wants all of history passes a range that covers it. **A range whose ends fall
     * mid-week returns partial buckets** — the boundary weeks hold the sessions
     * finished on the days asked for and no others — so callers charting whole weeks
     * should align the range to Mondays first, which is what `VolumeTrendUseCase` does.
     *
     * Sets with no `weight` or no `reps` (cardio, holds) count as zero volume
     * rather than dropping their week from the series.
     */
    @Query(
        """
        SELECT
            (s.completed_at / 86400000) - (((s.completed_at / 86400000) + 3) % 7) AS week_start,
            CAST(TOTAL(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS INTEGER) AS total_volume
        FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        INNER JOIN workout_session s ON s.id = se.session_id
        WHERE ws.is_completed = 1
            AND s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
            AND s.completed_at >= 0
            AND s.completed_at >= :startEpochDay * 86400000
            AND s.completed_at < :endEpochDayExclusive * 86400000
        GROUP BY week_start
        ORDER BY week_start ASC
        """,
    )
    fun getVolumeByWeek(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<List<WeeklyVolumeRow>>

    /**
     * Everything lifted over the half-open day range `[startEpochDay, endEpochDayExclusive)`,
     * in milligram-reps.
     *
     * The scalar twin of [getVolumeByWeek], for a caller that wants a period's total rather
     * than a series to draw — the quest board, which asks for a month, and a month is not a
     * whole number of the Monday weeks that series buckets by. Same filter, same expression,
     * no `GROUP BY`: one row back instead of one per week, and no summing in Kotlin of an
     * answer SQLite can state.
     *
     * It starts from the sessions in range and reaches their sets through the foreign-key
     * indexes, in under a millisecond. The quest board runs it once per replayed workout during
     * an import, so a plan that started from the sets instead would make the import grow with
     * the history; `LargeHistoryBenchmarkTest` holds it.
     *
     * A period with no lifting in it moved zero rather than an absent amount, and `TOTAL` says
     * so on its own: over no rows it is `0.0`, where `SUM` would be `NULL`. `getTrainingMillis`
     * answers zero for an empty period for the same reason.
     */
    @Query(
        """
        SELECT CAST(TOTAL(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS INTEGER)
        FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        INNER JOIN workout_session s ON s.id = se.session_id
        WHERE ws.is_completed = 1
            AND s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
            AND s.completed_at >= :startEpochDay * 86400000
            AND s.completed_at < :endEpochDayExclusive * 86400000
        """,
    )
    fun getVolumeIn(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<Long>

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
     *
     * Which sets fall in the range is decided exactly as [getVolumeByWeek] decides it —
     * finished sessions only, placed by the day the session was closed out — so a period's
     * breakdown is built from the same workouts as its total volume. Two figures on one
     * screen disagreeing about what "this month" holds is what sharing the filter avoids.
     *
     * `set_count` and `worked_directly` ride along rather than being a second query, because
     * they are three readings of the same fanned-out join: the progression screen's muscle map
     * ranks by sets, labels the figure with them, and needs to know which groups were prime
     * movers to draw them at full accent. A second aggregate over the same rows would be a
     * second scan of the whole set table to answer half of one question — see [MuscleVolumeRow].
     */
    @Query(
        """
        SELECT
            mg.id AS muscle_group_id,
            mg.name AS muscle_group_name,
            CAST(TOTAL(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS INTEGER) AS total_volume,
            CAST(TOTAL(
                CASE WHEN emg.is_primary = 1
                    THEN COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)
                    ELSE (COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) / 2
                END
            ) AS INTEGER) AS weighted_volume,
            COUNT(*) AS set_count,
            MAX(emg.is_primary) AS worked_directly
        FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        INNER JOIN workout_session s ON s.id = se.session_id
        INNER JOIN exercise_muscle_group emg ON emg.exercise_id = se.exercise_id
        INNER JOIN muscle_group mg ON mg.id = emg.muscle_group_id
        WHERE ws.is_completed = 1
            AND s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
            AND s.completed_at >= :startEpochDay * 86400000
            AND s.completed_at < :endEpochDayExclusive * 86400000
        GROUP BY mg.id, mg.name
        ORDER BY total_volume DESC, mg.name ASC
        """,
    )
    fun getVolumeByMuscleGroup(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<List<MuscleVolumeRow>>

    /**
     * How many working sets of [exerciseId] the user has ever completed.
     *
     * The per-exercise drill-down's `TOTAL SETS` card. A `COUNT` rather than a sum over
     * [getHistoryByExercise]: that query returns every completed set for one exercise across
     * all of history, which is the heaviest read in this file, and materialising thousands of
     * rows to render a four-digit figure is the shape this whole DAO exists to avoid.
     *
     * ### Which sets it counts
     *
     * Completed, non-warm-up, in a finished session — the same filter the drill-down applies
     * to everything else it draws, so this figure counts the very sets the `SETS` column of the
     * recent-attempts table is made of. It is not their *sum*: this has no window and no cap,
     * where that table is one timeframe's last few sessions. Warm-ups are out because the
     * screen is about how hard the lift was rather than how much work the week held, which is
     * the opposite of [getVolumeByWeek]'s reading of the same table and is stated there.
     */
    @Query(
        """
        SELECT COUNT(*) FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        INNER JOIN workout_session s ON s.id = se.session_id
        WHERE se.exercise_id = :exerciseId
            AND ws.is_completed = 1
            AND ws.set_type <> 'WARMUP'
            AND s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
        """,
    )
    fun countSetsByExercise(exerciseId: Long): Flow<Int>

    /**
     * One row per session [exerciseId] was performed in over the half-open day range
     * `[startEpochDay, endEpochDayExclusive)`, oldest first.
     *
     * Everything the per-exercise drill-down draws below its record cards: the bars are
     * `total_volume`, the one-rep-max line is estimated from the top set, and the
     * recent-attempts table is the whole row. One read for three drawings, so none of them can
     * disagree about what a session held — the reason this is an aggregate rather than three.
     *
     * ### A session, not a slot
     *
     * Grouped by `session_id`, so a workout that held the exercise twice — two slots of a
     * superset, a second run at it after accessories — is **one** attempt. That is the
     * grouping `PrDetectionUseCase` judges records by, and splitting it here would draw two
     * short attempts a lifter would not recognise beside a record built from both.
     *
     * ### Same filter and same bucketing as the rest of this file
     *
     * Completed sets of finished sessions, dated by the day the *session* was closed out, so
     * an attempt sits in the same period the hub's volume put it in. Warm-ups are excluded —
     * see [countSetsByExercise] — which also keeps a warm-up from ever being drawn as the top
     * set of a session.
     *
     * ### How the top set is chosen
     *
     * The heaviest, ties going to the one that got more reps out of the weight, and then to
     * the earlier row so the answer is stable. The CTE aggregates first and the subquery then
     * runs **once per attempt** rather than once per set, which is the same shape
     * [getPreviousSetsForSession] documents at length; a correlated subquery over the
     * historical rows instead grows with the square of the user's history.
     *
     * A bodyweight or timed attempt has no weight to rank on, so the ordering falls through to
     * reps and the row comes back with a null `top_weight` — which the caller renders as
     * `× 12` rather than as a lift. A timed attempt has no reps either, so it falls through
     * once more: the furthest set, and then the longest — a run is quoted by the run that
     * covered the most ground, a hold by the hold that lasted. Which of those is the *best*
     * effort (a faster 5 km is better than a slower one) is a records question, and #225's.
     *
     * The time and distance sums are what an attempt with no volume is quoted by instead —
     * see `ExerciseAttempt`.
     */
    @Query(
        """
        WITH attempt AS (
            SELECT
                se.session_id AS session_id,
                s.completed_at AS finished_at,
                COUNT(*) AS set_count,
                CAST(TOTAL(COALESCE(ws.weight, 0) * COALESCE(ws.reps, 0)) AS INTEGER) AS total_volume,
                CAST(TOTAL(COALESCE(ws.duration, 0)) AS INTEGER) AS timed_seconds,
                CAST(TOTAL(COALESCE(ws.distance, 0)) AS INTEGER) AS total_distance,
                MIN(ws.completed_at) AS first_set_at
            FROM workout_set ws
            INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
            INNER JOIN workout_session s ON s.id = se.session_id
            WHERE se.exercise_id = :exerciseId
                AND ws.is_completed = 1
                AND ws.set_type <> 'WARMUP'
                AND s.is_in_progress = 0
                AND s.completed_at IS NOT NULL
                AND s.completed_at >= :startEpochDay * 86400000
                AND s.completed_at < :endEpochDayExclusive * 86400000
            GROUP BY se.session_id
        )
        SELECT
            attempt.session_id AS session_id,
            attempt.finished_at AS finished_at,
            attempt.set_count AS set_count,
            attempt.total_volume AS total_volume,
            attempt.timed_seconds AS timed_seconds,
            attempt.total_distance AS total_distance,
            attempt.first_set_at AS first_set_at,
            top.weight AS top_weight,
            top.reps AS top_reps,
            top.duration AS top_duration,
            top.distance AS top_distance
        FROM attempt
        LEFT JOIN workout_set top ON top.id = (
            SELECT candidate.id
            FROM workout_set candidate
            INNER JOIN session_exercise cse ON candidate.session_exercise_id = cse.id
            WHERE cse.session_id = attempt.session_id
                AND cse.exercise_id = :exerciseId
                AND candidate.is_completed = 1
                AND candidate.set_type <> 'WARMUP'
            ORDER BY
                COALESCE(candidate.weight, 0) DESC,
                COALESCE(candidate.reps, 0) DESC,
                COALESCE(candidate.distance, 0) DESC,
                COALESCE(candidate.duration, 0) DESC,
                candidate.id ASC
            LIMIT 1
        )
        ORDER BY attempt.finished_at ASC, attempt.session_id ASC
        """,
    )
    fun getAttemptsByExercise(
        exerciseId: Long,
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Flow<List<ExerciseAttemptRow>>

    /**
     * Every exercise the user has actually logged, most recently trained first.
     *
     * What the per-exercise drill-down is chosen from when it is opened from the stats hub,
     * which arrives without a lift in hand. Built from the *history* rather than from the
     * catalogue on purpose: the catalogue is 873 seeded rows and picking from it is a search,
     * where this is a short list whose top entry is nearly always the one the user wants.
     *
     * Same filter as [countSetsByExercise], so an exercise's `set_count` here is the figure its
     * own screen opens with, and an exercise that has only ever been warmed up with does not
     * appear as something to drill into.
     *
     * The name rides along rather than being joined in Kotlin: the alternative is this list
     * plus a read of the whole catalogue to label a dozen rows.
     */
    @Query(
        """
        SELECT
            se.exercise_id AS exercise_id,
            e.name AS name,
            COUNT(*) AS set_count,
            MAX(s.completed_at) AS last_performed_at
        FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        INNER JOIN workout_session s ON s.id = se.session_id
        INNER JOIN exercise e ON e.id = se.exercise_id
        WHERE ws.is_completed = 1
            AND ws.set_type <> 'WARMUP'
            AND s.is_in_progress = 0
            AND s.completed_at IS NOT NULL
        GROUP BY se.exercise_id, e.name
        ORDER BY last_performed_at DESC, e.name ASC
        """,
    )
    fun getTrainedExercises(): Flow<List<TrainedExerciseRow>>
}
