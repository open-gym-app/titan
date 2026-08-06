package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("DELETE FROM template_exercise WHERE template_id = :templateId")
    suspend fun deleteExercises(templateId: Long)

    /**
     * Stores [template] and its [exercises] as one unit, returning the new template
     * id, which each exercise row is stamped with on the way in.
     *
     * The caller cannot do this itself: the exercises need an id that does not exist
     * until the template row is written, so splitting the two across two calls would
     * leave a window where a named template has no body — the state
     * `CreateTemplateUseCase` refuses to create. Positions come in already numbered;
     * this only fills in the id.
     */
    @Transaction
    suspend fun insertWithExercises(
        template: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseEntity>,
    ): Long {
        val templateId = insert(template)
        exercises.forEach { insertExercise(it.copy(templateId = templateId)) }
        return templateId
    }

    /**
     * Replaces `template.id`'s row and its whole exercise list in one transaction.
     *
     * Delete-then-insert rather than a diff, per `TemplateRepository.update`: an edit
     * screen hands back the list it has, and reconciling it row by row cannot be done
     * atomically outside the database. The delete is what `SET NULL`s
     * `session_exercise.template_exercise_id` on past sessions that referenced the
     * replaced rows; the sessions themselves are untouched.
     */
    @Transaction
    suspend fun updateWithExercises(
        template: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseEntity>,
    ) {
        update(template)
        deleteExercises(template.id)
        exercises.forEach { insertExercise(it.copy(templateId = template.id)) }
    }
}
