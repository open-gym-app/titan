package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.CollectedItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * The collectible inventory: one row per drop, and the newest of them as a flow.
 *
 * Unlike [AchievementDao] and [QuestCompletionDao] there is no insert-if-absent here, and no
 * boolean saying whether this call was the one that wrote. Both of those exist because an
 * unlock and a completion happen once and XP is paid on that answer; a drop can happen again,
 * so every write is a plain insert. See [CollectedItemEntity] for why that is the point rather
 * than an omission.
 */
@Dao
interface CollectedItemDao {

    /** Records one drop and returns its new rowid. Never merges with an existing row. */
    @Insert
    suspend fun insert(item: CollectedItemEntity): Long

    /**
     * The newest [limit] drops, most recently acquired first.
     *
     * A flow, for a quest log that is open while a session finishes behind it: a drop earned in
     * that moment fills in rather than waiting for the screen to be left and come back.
     *
     * The rowid is the tie-break rather than decoration. Every drop a single cascade produces
     * carries the *same* `acquired_at` — the triggering session's own moment — so ordering on
     * that column alone would leave their order up to SQLite, and the same list could come back
     * differently on the next read. Descending, so that among drops earned together the one
     * written last reads as the most recent, which is the order they were rolled in.
     *
     * Bounded by [limit] rather than returning the whole inventory: this table grows for the
     * life of an install, and its one reader draws a recent-drops section.
     */
    @Query("SELECT * FROM collected_item ORDER BY acquired_at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CollectedItemEntity>>
}
