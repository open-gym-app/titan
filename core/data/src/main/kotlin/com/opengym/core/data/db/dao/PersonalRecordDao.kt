package com.opengym.core.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.PersonalRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: PersonalRecordEntity): Long

    @Update
    suspend fun update(record: PersonalRecordEntity)

    @Query("DELETE FROM personal_record WHERE id = :id")
    suspend fun delete(id: Long)

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
    fun getLatestByType(exerciseId: Long, prType: String): Flow<PersonalRecordEntity?>

    /**
     * The [limit] most recently achieved records across every exercise, for the
     * "recent PRs" summary on the home and session-summary screens. Bounded by
     * `LIMIT`, and served by the `achieved_at` index rather than sorting the
     * whole table.
     */
    @Query("SELECT * FROM personal_record ORDER BY achieved_at DESC LIMIT :limit")
    fun getRecentRecords(limit: Int): Flow<List<PersonalRecordEntity>>

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
