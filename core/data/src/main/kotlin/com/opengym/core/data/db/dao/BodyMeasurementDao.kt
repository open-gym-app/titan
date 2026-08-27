package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.BodyMeasurementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyMeasurementDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(measurement: BodyMeasurementEntity): Long

    @Update
    suspend fun update(measurement: BodyMeasurementEntity)

    @Query("DELETE FROM body_measurement WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM body_measurement ORDER BY created_at DESC")
    fun getAll(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurement ORDER BY created_at DESC LIMIT 1")
    fun getLatest(): Flow<BodyMeasurementEntity?>
}
