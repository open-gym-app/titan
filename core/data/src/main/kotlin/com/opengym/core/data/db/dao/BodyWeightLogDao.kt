package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.BodyWeightLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyWeightLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: BodyWeightLogEntity): Long

    @Update
    suspend fun update(log: BodyWeightLogEntity)

    @Query("DELETE FROM body_weight_log WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM body_weight_log ORDER BY created_at DESC")
    fun getAll(): Flow<List<BodyWeightLogEntity>>

    @Query("SELECT * FROM body_weight_log ORDER BY created_at DESC LIMIT 1")
    fun getLatest(): Flow<BodyWeightLogEntity?>
}
