package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.titan.core.data.db.entity.ProgressPhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressPhotoDao {

    /** Most recently taken first, and newest added first within one moment. */
    @Query("SELECT * FROM progress_photo ORDER BY taken_at DESC, id DESC")
    fun getAll(): Flow<List<ProgressPhotoEntity>>

    @Query("SELECT * FROM progress_photo WHERE id = :id")
    suspend fun getById(id: Long): ProgressPhotoEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: ProgressPhotoEntity): Long

    @Query("DELETE FROM progress_photo WHERE id = :id")
    suspend fun delete(id: Long)
}
