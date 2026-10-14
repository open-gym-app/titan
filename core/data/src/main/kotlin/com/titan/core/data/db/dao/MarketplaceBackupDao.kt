package com.titan.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.MarketplaceTemplateEntity

/** Whole-table reads, inserts and deletes over `marketplace_template` for export and import. See [CatalogueBackupDao]. */
interface MarketplaceBackupDao {

    @Query("SELECT * FROM marketplace_template ORDER BY remote_id LIMIT :limit OFFSET :offset")
    suspend fun readMarketplaceTemplates(
        limit: Int,
        offset: Int,
    ): List<MarketplaceTemplateEntity>

    @Insert
    suspend fun insertMarketplaceTemplates(rows: List<MarketplaceTemplateEntity>)

    @Query("DELETE FROM marketplace_template")
    suspend fun deleteMarketplaceTemplates()
}
