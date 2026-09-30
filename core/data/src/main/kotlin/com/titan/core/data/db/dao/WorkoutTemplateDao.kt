package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.titan.core.data.db.entity.TemplateExerciseEntity
import com.titan.core.data.db.entity.TemplateSetEntity
import com.titan.core.data.db.entity.WorkoutTemplateEntity
import com.titan.core.data.db.projection.TemplateExerciseWithSets
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutTemplateDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(template: WorkoutTemplateEntity): Long

    /** Returns the number of rows changed: 0 when `template.id` matches nothing. */
    @Update
    suspend fun update(template: WorkoutTemplateEntity): Int

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

    /**
     * The sets of one exercise, numbered from 1 in the order they are performed.
     *
     * Written in bulk rather than one at a time, unlike a session's sets: a template's
     * body is only ever replaced whole, so there is no single-set write for the store to
     * offer and nothing that would need one.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSets(sets: List<TemplateSetEntity>)

    @Transaction
    @Query("SELECT * FROM template_exercise WHERE template_id = :templateId ORDER BY position ASC")
    fun getExercises(templateId: Long): Flow<List<TemplateExerciseWithSets>>

    /**
     * Every template's exercises in one query, for the screens that render a row per
     * template and need something off each one's body — see
     * `TemplateRepository.getAllExercises`.
     *
     * Ordered by template and then by position, so a caller grouping by `template_id`
     * gets each group already in the order the contract promises.
     */
    @Transaction
    @Query("SELECT * FROM template_exercise ORDER BY template_id ASC, position ASC")
    fun getAllExercises(): Flow<List<TemplateExerciseWithSets>>

    @Query("DELETE FROM template_exercise WHERE template_id = :templateId")
    suspend fun deleteExercises(templateId: Long)

    /**
     * Stores [template] and its [exercises] as one unit, returning the new template
     * id, which each exercise row is stamped with on the way in.
     *
     * The caller cannot do this itself: the exercises need an id that does not exist
     * until the template row is written, and each exercise's sets need an id that does
     * not exist until *it* is written — so splitting this up would leave windows where a
     * named template has no body, or an exercise has no sets. Positions and set numbers
     * come in already numbered; this only fills in the two ids.
     */
    @Transaction
    suspend fun insertWithExercises(
        template: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseWithSets>,
    ): Long {
        val templateId = insert(template)
        insertBodies(templateId, exercises)
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
     *
     * An absent `template.id` returns early rather than falling through. Without the
     * guard the outcome depends on the *list*: an empty one leaves [update] and
     * [deleteExercises] as silent no-ops and the call succeeds, while a non-empty one
     * reaches [insertExercise] with a `template_id` that has no parent row and fails
     * the foreign key. Both write nothing, so the transaction was never the problem —
     * but a caller cannot be asked to predict which of the two it gets, so the no-op
     * is made the answer in both, matching [delete].
     */
    @Transaction
    suspend fun updateWithExercises(
        template: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseWithSets>,
    ) {
        if (update(template) == 0) return
        // The sets go with them: `template_set` cascades from `template_exercise`, so
        // deleting the body deletes what it planned.
        deleteExercises(template.id)
        insertBodies(template.id, exercises)
    }

    /**
     * Writes each exercise under [templateId] and then the sets it plans under the id it
     * was just given.
     *
     * Shared by both write paths rather than written twice, which is the only way the
     * insert and the replace can be guaranteed to store the same thing. Not transactional
     * on its own — both callers already are, and this must not commit half a body.
     */
    suspend fun insertBodies(
        templateId: Long,
        exercises: List<TemplateExerciseWithSets>,
    ) {
        exercises.forEach { body ->
            val exerciseId = insertExercise(body.exercise.copy(templateId = templateId))
            if (body.sets.isNotEmpty()) {
                insertSets(body.sets.map { it.copy(templateExerciseId = exerciseId) })
            }
        }
    }
}
