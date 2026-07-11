package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.WorkoutTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutTemplateDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(template: WorkoutTemplateEntity): Long

    @Update
    suspend fun update(template: WorkoutTemplateEntity)

    @Query("DELETE FROM workout_template WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM workout_template ORDER BY name ASC")
    fun getAll(): Flow<List<WorkoutTemplateEntity>>

    @Query("SELECT * FROM workout_template WHERE id = :id")
    fun getById(id: Long): Flow<WorkoutTemplateEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExercise(templateExercise: TemplateExerciseEntity): Long

    @Update
    suspend fun updateExercise(templateExercise: TemplateExerciseEntity)

    @Query("DELETE FROM template_exercise WHERE id = :id")
    suspend fun deleteExercise(id: Long)

    @Query("SELECT * FROM template_exercise WHERE template_id = :templateId ORDER BY position ASC")
    fun getExercises(templateId: Long): Flow<List<TemplateExerciseEntity>>
}
