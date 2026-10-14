package com.titan.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.GymEntity

/** Whole-table reads, inserts and deletes over `gym` for export and import. See [CatalogueBackupDao]. */
interface GymBackupDao {

    @Query("SELECT * FROM gym ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readGyms(
        limit: Int,
        offset: Int,
    ): List<GymEntity>

    @Insert
    suspend fun insertGyms(rows: List<GymEntity>)

    @Query("DELETE FROM gym")
    suspend fun deleteGyms()
}
