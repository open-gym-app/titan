package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opengym.core.data.db.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

/**
 * What the user has unlocked. Writes are insert-if-absent and reads come in both shapes the
 * gamification surfaces need — the whole set at once for the cascade, a flow for a screen.
 */
@Dao
interface AchievementDao {

    /**
     * The raw write. Returns the new rowid, or [NOT_INSERTED] when a row for that
     * achievement was already there — which is what [insertIfAbsent] turns into its answer.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(achievement: AchievementEntity): Long

    /**
     * Records [achievement] as unlocked, and answers whether **this call** wrote the row.
     *
     * Idempotent, and atomically so: `INSERT OR IGNORE` is one statement, so the check for
     * "already unlocked" cannot be interleaved with the write the way a read followed by an
     * insert could. That is what the caller pays XP on — two cascades running at once must
     * not both be told they were the one to unlock it, and must not both pay for it.
     *
     * A repeat call writes nothing and leaves [AchievementEntity.unlockedAt] as first
     * recorded: the earliest moment the milestone was reached is the true one.
     */
    suspend fun insertIfAbsent(achievement: AchievementEntity): Boolean = insertOrIgnore(achievement) != NOT_INSERTED

    /**
     * Every unlock, earliest first — for a screen that lists them (#104).
     *
     * Ordered by when the milestone was earned rather than by id, so the list reads as the
     * user's own history.
     */
    @Query("SELECT * FROM achievement ORDER BY unlocked_at ASC")
    fun getAll(): Flow<List<AchievementEntity>>

    /**
     * The ids of everything unlocked, read once.
     *
     * A suspend read of the whole set rather than a flow: the cascade evaluates the entire
     * catalogue against it right after a session finishes, and a milestone unlocked seconds
     * ago must be in the answer — which a hot flow's latest emission is not guaranteed to
     * be. Bounded by the catalogue, so reading all of it costs nothing worth optimising.
     */
    @Query("SELECT achievement_id FROM achievement")
    suspend fun getAllIdsOnce(): List<String>
}
