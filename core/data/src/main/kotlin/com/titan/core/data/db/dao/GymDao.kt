package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.titan.core.data.db.entity.GymEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GymDao {

    /** Every gym, by name ignoring case, then by id so equal names read in a stable order. */
    @Query("SELECT * FROM gym ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<GymEntity>>

    @Query("SELECT * FROM gym WHERE id = :id")
    suspend fun getById(id: Long): GymEntity?

    /** Inserts when the id is 0 or unknown, otherwise replaces; returns the row's id. */
    @Upsert
    suspend fun upsert(gym: GymEntity): Long

    @Query("DELETE FROM gym WHERE id = :id")
    suspend fun deleteById(id: Long)
}
