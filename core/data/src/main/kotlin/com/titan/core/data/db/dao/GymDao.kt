package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.titan.core.data.db.entity.GymEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GymDao {

    /**
     * Inserts a row with id 0 as a new gym and returns its id; overwrites the row that already
     * has the id otherwise, and returns -1 as Room does for an update.
     */
    @Upsert
    suspend fun upsert(gym: GymEntity): Long

    @Query("DELETE FROM gym WHERE id = :id")
    suspend fun delete(id: Long)

    // `id` breaks the tie between two gyms of the same name, so the list does not reshuffle.
    @Query("SELECT * FROM gym ORDER BY name COLLATE NOCASE, id")
    fun getAll(): Flow<List<GymEntity>>

    @Query("SELECT * FROM gym WHERE id = :id")
    fun getById(id: Long): Flow<GymEntity?>
}
