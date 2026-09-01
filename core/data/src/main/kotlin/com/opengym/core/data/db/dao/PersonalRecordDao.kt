package com.opengym.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.data.db.projection.PersonalRecordEntryRow
import com.opengym.core.data.db.projection.RecordTallyRow
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: PersonalRecordEntity): Long

    @Update
    suspend fun update(record: PersonalRecordEntity)

    @Query("DELETE FROM personal_record WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Writes [record] under [exerciseId]/[prType], but only if [isBetter] accepts the row
     * [getLatestByTypeOnce] currently returns for that exercise and type — evaluated and
     * acted on inside one transaction, so a second call for the same exercise and type
     * cannot read the same "current" row before this one's insert commits. Returns the new
     * row's id, or **null** if [isBetter] rejected the current one.
     *
     * A suspend one-shot read rather than a `Flow`: a `Flow` has no "now", and reading one
     * inside a transaction would not itself make the read part of it.
     */
    @Transaction
    suspend fun insertIfBetter(
        exerciseId: Long,
        prType: String,
        record: PersonalRecordEntity,
        isBetter: (current: PersonalRecordEntity?) -> Boolean,
    ): Long? {
        val current = getLatestByTypeOnce(exerciseId, prType)
        if (!isBetter(current)) return null
        return insert(record)
    }

    /**
     * How many records have ever been broken, across every exercise and type.
     *
     * `COUNT(*)` over the whole table is the honest figure because rows are only ever inserted
     * — [insertIfBetter] never replaces one — so the table is the history rather than a set of
     * current bests. A one-shot for the post-session cascade, which needs the total including
     * the rows it has just written.
     */
    @Query("SELECT COUNT(*) FROM personal_record")
    suspend fun count(): Int

    /**
     * How many records were broken over the half-open millisecond range
     * `[startEpochMillis, endEpochMillisExclusive)`, and across how many exercises.
     *
     * The stats hub's `PRs HIT` tile. In **milliseconds** rather than epoch days, unlike the
     * aggregates on [com.opengym.core.data.db.dao.WorkoutSetDao], because `achieved_at` is what
     * this table indexes and translating a day range once at the repository is cheaper than
     * dividing every row by 86,400,000 and losing the index to do it.
     *
     * Counted by when the record was **achieved**, which is the set's own timestamp rather than
     * the session's — the same instant the record was written against, so a period's PR count
     * belongs to the period the lift happened in. That is a hair's breadth from
     * `WorkoutSetDao.getVolumeByWeek`'s rule (the day the *session* was closed out) and they
     * can disagree only for a workout that ran across midnight on the period's boundary.
     * Living with that is better than joining three tables to re-derive a session id the record
     * does not carry.
     *
     * A `Flow`, not a `suspend` one-shot like [count]: this one is read by a screen that has to
     * repaint when the cascade writes a record behind it, where [count] is read *by* the
     * cascade and needs its own writes in the same breath.
     */
    @Query(
        """
        SELECT
            COUNT(*) AS record_count,
            COUNT(DISTINCT exercise_id) AS exercise_count
        FROM personal_record
        WHERE achieved_at >= :startEpochMillis AND achieved_at < :endEpochMillisExclusive
        """,
    )
    fun getRecordTally(
        startEpochMillis: Long,
        endEpochMillisExclusive: Long,
    ): Flow<RecordTallyRow>

    @Query("SELECT * FROM personal_record WHERE exercise_id = :exerciseId ORDER BY achieved_at DESC")
    fun getByExercise(exerciseId: Long): Flow<List<PersonalRecordEntity>>

    /**
     * Paginated variant of [getByExercise]. Records accumulate over time, so the
     * per-exercise records screen can page through the history while [getByExercise]
     * stays available for callers that need the whole set at once.
     */
    @Query("SELECT * FROM personal_record WHERE exercise_id = :exerciseId ORDER BY achieved_at DESC")
    fun getByExercisePaginated(exerciseId: Long): PagingSource<Int, PersonalRecordEntity>

    @Query(
        """
        SELECT * FROM personal_record
        WHERE exercise_id = :exerciseId AND pr_type = :prType
        ORDER BY achieved_at DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestByTypeOnce(
        exerciseId: Long,
        prType: String,
    ): PersonalRecordEntity?

    /**
     * The [limit] most recently achieved records across every exercise, for the
     * "recent PRs" summary on the home and session-summary screens. Bounded by
     * `LIMIT`, and served by the `achieved_at` index rather than sorting the
     * whole table.
     */

    @Query("SELECT * FROM personal_record ORDER BY achieved_at DESC LIMIT :limit")
    fun getRecentRecords(limit: Int): Flow<List<PersonalRecordEntity>>

    /**
     * One window of every record, newest first, with what each one beat and where it was set.
     *
     * ### The improvement is a window function, not a query per row
     *
     * `LAG` over the records of the same exercise *and* kind, ordered oldest first, is the figure
     * this record replaced. Doing it here is the difference between one query for a page and one
     * query per visible row while the user scrolls — which is what #103 rules out. SQLite has had
     * window functions since 3.25, which is below every API level this app runs on.
     *
     * The value being compared is chosen by kind, because each kind lives in its own column and
     * `MAX_VOLUME` lives in `weight` — see `PersonalRecordEntry.value`, which makes the same
     * choice for the row that is drawn. The two agree by inspection rather than by construction,
     * which is a seam worth knowing about: a seventh kind has to be added in both.
     *
     * ### Ordering, filtering and the index
     *
     * `achieved_at DESC` so the existing index on that column serves the sort. The type filter is
     * `:type IS NULL OR pr_type = :type`, so "all" and "one kind" are one query rather than two
     * that could drift apart — and the window is partitioned before the filter is applied, so a
     * filtered list still knows what each record beat.
     *
     * ### Why the joins are `LEFT`
     *
     * An exercise deleted from the catalogue, or a set whose session row is gone, must not delete
     * the user's record from their own history. Both joins therefore keep the record and leave the
     * name null or the badge false.
     *
     * @param type the kind to list, or null for all of them.
     * @param limit how many rows to return.
     * @param offset how many to skip.
     */
    @Query(
        """
        SELECT
            record.id AS id,
            record.exercise_id AS exercise_id,
            record.workout_set_id AS workout_set_id,
            record.pr_type AS pr_type,
            record.weight AS weight,
            record.reps AS reps,
            record.distance AS distance,
            record.duration AS duration,
            record.estimated_1rm AS estimated_1rm,
            record.achieved_at AS achieved_at,
            record.previous_value AS previous_value,
            exercise.name AS exercise_name,
            CASE
                WHEN session_exercise.session_id IS NOT NULL
                    AND session_exercise.session_id = (
                        SELECT id FROM workout_session
                        WHERE is_in_progress = 0 AND completed_at IS NOT NULL
                        ORDER BY completed_at DESC
                        LIMIT 1
                    )
                THEN 1 ELSE 0
            END AS is_recent
        FROM (
            SELECT
                *,
                LAG(
                    CASE pr_type
                        WHEN 'MAX_WEIGHT' THEN weight
                        WHEN 'MAX_VOLUME' THEN weight
                        WHEN 'MAX_REPS' THEN reps
                        WHEN 'ESTIMATED_1RM' THEN estimated_1rm
                        WHEN 'MAX_DISTANCE' THEN distance
                        WHEN 'BEST_PACE' THEN duration
                    END
                ) OVER (
                    PARTITION BY exercise_id, pr_type
                    ORDER BY achieved_at ASC, id ASC
                ) AS previous_value
            FROM personal_record
        ) AS record
        LEFT JOIN exercise ON exercise.id = record.exercise_id
        LEFT JOIN workout_set ON workout_set.id = record.workout_set_id
        LEFT JOIN session_exercise ON session_exercise.id = workout_set.session_exercise_id
        WHERE :type IS NULL OR record.pr_type = :type
        ORDER BY record.achieved_at DESC, record.id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getRecordsPage(
        type: String?,
        limit: Int,
        offset: Int,
    ): List<PersonalRecordEntryRow>

    /**
     * How many records exist, re-emitted whenever the table changes.
     *
     * The invalidation signal for the list's own `PagingSource`, which — unlike Room's — is not
     * told when the table moves under it. See `PersonalRecordRepository.observeRecordCount`.
     */
    @Query("SELECT COUNT(*) FROM personal_record")
    fun observeCount(): Flow<Int>

    /**
     * One record per exercise — the one worth putting on a list row.
     *
     * A row has space for a single figure and an exercise accumulates records of several
     * kinds off the same sets, so something has to choose. The order below is what a lifter
     * would quote: what you lifted, then what you lifted it for, then how far or how fast,
     * and only then the two derived figures — an estimated 1RM is arithmetic over a set that
     * already has a `MAX_WEIGHT` record, and a volume record is a property of a session
     * rather than of a lift.
     *
     * Ties inside a kind go to the most recent, which is also the best: a record of a given
     * kind is only written when it beats the one before it.
     *
     * The `pr_type` names are spelled out because that is what the column holds — the
     * persisted form of `PrType`, pinned by the domain's `PersistedEnumTest`, not an
     * incidental rendering of it.
     *
     * ### Why the query is shaped the way it is
     *
     * The obvious spelling — `WHERE best.id = (SELECT … WHERE candidate.exercise_id =
     * best.exercise_id …)` — is correlated on the *outer row*, so SQLite runs it once per
     * record rather than once per exercise, and the `ORDER BY` cannot use the
     * `(exercise_id, achieved_at)` index because its leading key is an expression, so each
     * evaluation builds a temporary b-tree. That measured **1,263 ms** against
     * `LargeHistoryBenchmarkTest`'s 10,000-record fixture, over a 500 ms budget.
     *
     * Driving it from `SELECT DISTINCT exercise_id` instead pins the subquery to one
     * evaluation per exercise that has any record at all — a few hundred on a real device —
     * and turns the outer half into an id lookup. Same rows, same order of preference.
     * `LargeHistoryBenchmarkTest` holds it to the budget, so this shape is load-bearing.
     */
    @Query(
        """
        SELECT * FROM personal_record
        WHERE id IN (
            SELECT (
                SELECT candidate.id FROM personal_record candidate
                WHERE candidate.exercise_id = present.exercise_id
                ORDER BY
                    CASE candidate.pr_type
                        WHEN 'MAX_WEIGHT' THEN 0
                        WHEN 'MAX_REPS' THEN 1
                        WHEN 'MAX_DISTANCE' THEN 2
                        WHEN 'BEST_PACE' THEN 3
                        WHEN 'ESTIMATED_1RM' THEN 4
                        WHEN 'MAX_VOLUME' THEN 5
                        ELSE 6
                    END ASC,
                    candidate.achieved_at DESC
                LIMIT 1
            )
            FROM (SELECT DISTINCT exercise_id FROM personal_record) present
        )
        """,
    )
    fun getHeadlineRecords(): Flow<List<PersonalRecordEntity>>
}
