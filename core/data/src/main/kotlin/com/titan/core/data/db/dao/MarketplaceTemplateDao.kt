package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.titan.core.data.db.entity.MarketplaceTemplateEntity

@Dao
interface MarketplaceTemplateDao {

    @Query("SELECT * FROM marketplace_template ORDER BY remote_id")
    suspend fun getAll(): List<MarketplaceTemplateEntity>

    @Query("SELECT * FROM marketplace_template WHERE is_hidden = 0 ORDER BY remote_id")
    suspend fun getVisible(): List<MarketplaceTemplateEntity>

    @Upsert
    suspend fun upsertAll(rows: List<MarketplaceTemplateEntity>)

    @Query("UPDATE marketplace_template SET last_seen_at = :seenAt WHERE remote_id IN (:ids)")
    suspend fun markSeen(
        ids: List<String>,
        seenAt: Long,
    )

    @Query("UPDATE marketplace_template SET is_hidden = 1 WHERE remote_id IN (:ids)")
    suspend fun hide(ids: List<String>)
}
