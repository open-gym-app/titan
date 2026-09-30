package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.titan.core.data.db.entity.QuestCompletionEntity
import kotlinx.coroutines.flow.Flow

/**
 * What the user has completed, per quest and per period.
 *
 * Shaped like [AchievementDao] — insert-if-absent, with the period as the extra half of
 * every key. See [QuestCompletionEntity] for why that pair is the primary key.
 */
@Dao
interface QuestCompletionDao {

    /**
     * The raw write. Returns the new rowid, or [NOT_INSERTED] when a row for that quest and
     * period was already there — which is what [insertIfAbsent] turns into its answer.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(completion: QuestCompletionEntity): Long

    /**
     * Records [completion] and answers whether **this call** wrote the row.
     *
     * Idempotent, and atomically so, for the reason [AchievementDao.insertIfAbsent] is:
     * `INSERT OR IGNORE` is one statement, so the check for "already completed" cannot be
     * interleaved with the write the way a read followed by an insert could. That answer is
     * what the caller pays XP on, and two cascades running at once must not both be told they
     * were first.
     *
     * A repeat call writes nothing and leaves [QuestCompletionEntity.completedAt] as first
     * recorded: the earliest session that met the target is the one that earned it.
     */
    suspend fun insertIfAbsent(completion: QuestCompletionEntity): Boolean = insertOrIgnore(completion) != NOT_INSERTED

    /**
     * The quest ids completed in the period opening on [periodStart], as a flow.
     *
     * For a board that is open while a session finishes behind it: a quest that completes must
     * fill in rather than waiting for the screen to be left and come back.
     *
     * Scoped to one period rather than returning every completion ever: a board only ever draws
     * the period it is in, and the table grows without bound over an install's life.
     */
    @Query("SELECT quest_id FROM quest_completion WHERE period_start = :periodStart")
    fun observeCompletedIds(periodStart: Long): Flow<List<String>>
}
