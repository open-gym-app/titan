package com.titan.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.FoodSeenEntity
import com.titan.core.data.db.entity.PetPowerSnapshotEntity
import com.titan.core.data.db.entity.PetSparEntity
import com.titan.core.data.db.entity.PetUnlockEntity

/**
 * The backup half of the titan's progress tables — what it has seen, unlocked and fought — beside
 * [TitanBackupDao]'s for the titan itself. Mixed into [BackupDao].
 */
interface TitanProgressBackupDao {

    @Query("SELECT * FROM food_seen ORDER BY collectible_id LIMIT :limit OFFSET :offset")
    suspend fun readFoodSeen(
        limit: Int,
        offset: Int,
    ): List<FoodSeenEntity>

    @Insert
    suspend fun insertFoodSeen(rows: List<FoodSeenEntity>)

    @Query("DELETE FROM food_seen")
    suspend fun deleteFoodSeen()

    @Query("SELECT * FROM pet_unlock ORDER BY `key` LIMIT :limit OFFSET :offset")
    suspend fun readPetUnlocks(
        limit: Int,
        offset: Int,
    ): List<PetUnlockEntity>

    @Insert
    suspend fun insertPetUnlocks(rows: List<PetUnlockEntity>)

    @Query("DELETE FROM pet_unlock")
    suspend fun deletePetUnlocks()

    @Query("SELECT * FROM pet_power_snapshot ORDER BY pet_id, week_start LIMIT :limit OFFSET :offset")
    suspend fun readPowerSnapshots(
        limit: Int,
        offset: Int,
    ): List<PetPowerSnapshotEntity>

    @Insert
    suspend fun insertPowerSnapshots(rows: List<PetPowerSnapshotEntity>)

    @Query("DELETE FROM pet_power_snapshot")
    suspend fun deletePowerSnapshots()

    @Query("SELECT * FROM pet_spar ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readSpars(
        limit: Int,
        offset: Int,
    ): List<PetSparEntity>

    @Insert
    suspend fun insertSpars(rows: List<PetSparEntity>)

    @Query("DELETE FROM pet_spar")
    suspend fun deleteSpars()
}
