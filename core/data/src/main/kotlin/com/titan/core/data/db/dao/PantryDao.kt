package com.titan.core.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.PetFeedEntity
import kotlinx.coroutines.flow.Flow

/** One food drop still in stock, with what the pantry says about it. */
data class InStockFood(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "collectible_id") val collectibleId: String,
    @ColumnInfo(name = "acquired_at") val acquiredAt: Long,
    @ColumnInfo(name = "session_id") val sessionId: Long?,
    @ColumnInfo(name = "session_name") val sessionName: String?,
    @ColumnInfo(name = "session_finished_at") val sessionFinishedAt: Long?,
    @ColumnInfo(name = "seen_up_to") val seenUpTo: Long,
)

@Dao
interface PantryDao {

    /** Every drop of [foodIds] no feed names. An anti-join, like the unhatched eggs. */
    @Query(
        """
        SELECT ci.id, ci.collectible_id, ci.acquired_at, ci.session_id,
            ws.name AS session_name, ws.completed_at AS session_finished_at,
            COALESCE(fs.seen_up_to, 0) AS seen_up_to
        FROM collected_item ci
        LEFT JOIN pet_feed pf ON pf.collected_item_id = ci.id
        LEFT JOIN workout_session ws ON ws.id = ci.session_id
        LEFT JOIN food_seen fs ON fs.collectible_id = ci.collectible_id
        WHERE pf.collected_item_id IS NULL AND ci.collectible_id IN (:foodIds)
        ORDER BY ci.acquired_at, ci.id
        """,
    )
    fun observeInStock(foodIds: List<String>): Flow<List<InStockFood>>

    @Query(
        """
        SELECT ci.id FROM collected_item ci
        LEFT JOIN pet_feed pf ON pf.collected_item_id = ci.id
        WHERE pf.collected_item_id IS NULL AND ci.collectible_id = :foodId
        ORDER BY ci.acquired_at, ci.id
        LIMIT 1
        """,
    )
    suspend fun oldestInStock(foodId: String): Long?

    /** Throws `SQLiteConstraintException` for an item already eaten. */
    @Insert
    suspend fun insertFeed(feed: PetFeedEntity)

    @Query(
        """
        INSERT OR REPLACE INTO food_seen (collectible_id, seen_up_to)
        SELECT :foodId, COALESCE(MAX(id), 0) FROM collected_item WHERE collectible_id = :foodId
        """,
    )
    suspend fun markSeen(foodId: String)
}
