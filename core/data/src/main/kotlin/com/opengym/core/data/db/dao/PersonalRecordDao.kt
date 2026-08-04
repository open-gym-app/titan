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
}
