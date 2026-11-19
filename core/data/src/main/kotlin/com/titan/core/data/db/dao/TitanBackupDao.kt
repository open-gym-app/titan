package com.titan.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.titan.core.data.db.entity.FoodSeenEntity
import com.titan.core.data.db.entity.PetEntity
import com.titan.core.data.db.entity.PetFeedEntity
import com.titan.core.data.db.entity.PetStateEntity

/**
 * The backup half of the Titans milestone's tables: a page read, a bulk insert and a wipe per
 * table, as [GamificationBackupDao] has for the gamification tables. Mixed into [BackupDao].
 */
interface TitanBackupDao {

    @Query("SELECT * FROM pet ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readPets(
        limit: Int,
        offset: Int,
    ): List<PetEntity>

    @Insert
    suspend fun insertPets(rows: List<PetEntity>)

    @Query("DELETE FROM pet")
    suspend fun deletePets()

    @Query("SELECT * FROM pet_state ORDER BY pet_id LIMIT :limit OFFSET :offset")
    suspend fun readPetStates(
        limit: Int,
        offset: Int,
    ): List<PetStateEntity>

    @Insert
    suspend fun insertPetStates(rows: List<PetStateEntity>)

    @Query("DELETE FROM pet_state")
    suspend fun deletePetStates()

    @Query("SELECT * FROM pet_feed ORDER BY collected_item_id LIMIT :limit OFFSET :offset")
    suspend fun readPetFeeds(
        limit: Int,
        offset: Int,
    ): List<PetFeedEntity>

    @Insert
    suspend fun insertPetFeeds(rows: List<PetFeedEntity>)

    @Query("DELETE FROM pet_feed")
    suspend fun deletePetFeeds()

    @Query("SELECT * FROM food_seen ORDER BY collectible_id LIMIT :limit OFFSET :offset")
    suspend fun readFoodSeen(
        limit: Int,
        offset: Int,
    ): List<FoodSeenEntity>

    @Insert
    suspend fun insertFoodSeen(rows: List<FoodSeenEntity>)

    @Query("DELETE FROM food_seen")
    suspend fun deleteFoodSeen()
}
