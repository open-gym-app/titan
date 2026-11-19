package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.titan.core.data.db.entity.PetStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PetStateDao {

    @Query("SELECT * FROM pet_state WHERE pet_id = :petId")
    fun observe(petId: Long): Flow<PetStateEntity?>

    @Query("SELECT * FROM pet_state WHERE pet_id = :petId")
    suspend fun get(petId: Long): PetStateEntity?

    @Upsert
    suspend fun upsert(state: PetStateEntity)
}
