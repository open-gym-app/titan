package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opengym.core.data.db.entity.MuscleGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MuscleGroupDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(muscleGroups: List<MuscleGroupEntity>): List<Long>

    @Query("SELECT * FROM muscle_group ORDER BY name ASC")
    fun getAll(): Flow<List<MuscleGroupEntity>>

    @Query("SELECT * FROM muscle_group WHERE id = :id")
    fun getById(id: Long): Flow<MuscleGroupEntity?>
}
