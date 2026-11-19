package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.titan.core.data.db.entity.PetUnlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PetUnlockDao {

    @Query("SELECT `key` FROM pet_unlock")
    fun observeKeys(): Flow<List<String>>

    /** One row id per row, or -1 for a key already held. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(rows: List<PetUnlockEntity>): List<Long>
}
