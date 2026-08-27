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

    // `id` breaks the tie on `created_at`, which two entries share as soon as a user logs a
    // weigh-in and a set of circumferences for the same moment: without it "the latest entry"
    // is whichever row the plan happens to reach first, so the form could pre-fill from the
    // one the user did not just write. `id` is the rowid, so `created_at DESC, id DESC` is
    // exactly the reverse of `index_body_measurement_created_at` and costs no sort.
    @Query("SELECT * FROM body_measurement ORDER BY created_at DESC, id DESC")
    fun getAll(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurement ORDER BY created_at DESC, id DESC LIMIT 1")
    fun getLatest(): Flow<BodyMeasurementEntity?>
}
