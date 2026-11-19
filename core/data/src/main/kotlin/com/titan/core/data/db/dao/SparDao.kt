package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.PetPowerSnapshotEntity
import com.titan.core.data.db.entity.PetSparEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SparDao {

    @Query("SELECT * FROM pet_power_snapshot WHERE pet_id = :petId ORDER BY week_start")
    fun observeSnapshots(petId: Long): Flow<List<PetPowerSnapshotEntity>>

    @Query("SELECT * FROM pet_spar WHERE pet_id = :petId ORDER BY fought_at, id")
    fun observeBouts(petId: Long): Flow<List<PetSparEntity>>

    @Insert
    suspend fun insertBout(bout: PetSparEntity)

    @Query("SELECT COUNT(*) FROM pet_spar WHERE won = 1")
    suspend fun countWins(): Int
}
