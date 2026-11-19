package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.titan.core.data.db.entity.PetLoadoutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LoadoutDao {

    @Query("SELECT * FROM pet_loadout WHERE pet_id = :petId")
    fun observe(petId: Long): Flow<List<PetLoadoutEntity>>

    @Upsert
    suspend fun upsert(row: PetLoadoutEntity)

    @Query("DELETE FROM pet_loadout WHERE pet_id = :petId AND slot = :slot")
    suspend fun delete(
        petId: Long,
        slot: String,
    )
}
