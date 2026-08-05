package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.ExerciseTagEntity
import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.data.db.projection.ExerciseMuscleGroupLinkRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(exercise: ExerciseEntity): Long

    @Update
    suspend fun update(exercise: ExerciseEntity)

    @Query("DELETE FROM exercise WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM exercise ORDER BY name ASC")
    fun getAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE id = :id")
    fun getById(id: Long): Flow<ExerciseEntity?>

    @Query("SELECT * FROM exercise WHERE exercise_type = :type ORDER BY name ASC")
    fun getByType(type: String): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE equipment_type = :equipmentType ORDER BY name ASC")
    fun getByEquipmentType(equipmentType: String): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE discipline = :discipline ORDER BY name ASC")
    fun getByDiscipline(discipline: String): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercise WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchByPartialName(query: String): Flow<List<ExerciseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMuscleGroup(ref: ExerciseMuscleGroupEntity)

    @Query("DELETE FROM exercise_muscle_group WHERE exercise_id = :exerciseId AND muscle_group_id = :muscleGroupId")
    suspend fun removeMuscleGroup(exerciseId: Long, muscleGroupId: Long)

    /**
     * The muscle groups [exerciseId] trains, each with the join row's `is_primary`
     * flag, primary first and then by name.
     *
     * Returns [ExerciseMuscleGroupLinkRow] rather than the bare muscle group so the
     * flag survives the query: volume attribution weights a primary link at 100% and
     * a secondary one at 50%, and recovering that with a second read would mean
     * walking the same join twice.
     */
    @Query(
        """
        SELECT
            mg.id AS muscle_group_id,
            mg.name AS muscle_group_name,
            mg.icon AS muscle_group_icon,
            emg.is_primary AS is_primary
        FROM muscle_group mg
        INNER JOIN exercise_muscle_group emg ON mg.id = emg.muscle_group_id
        WHERE emg.exercise_id = :exerciseId
        ORDER BY emg.is_primary DESC, mg.name ASC
        """,
    )
    fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLinkRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTag(ref: ExerciseTagEntity)

    @Query("DELETE FROM exercise_tag WHERE exercise_id = :exerciseId AND tag_id = :tagId")
    suspend fun removeTag(exerciseId: Long, tagId: Long)

    @Query(
        """
        SELECT t.* FROM tag t
        INNER JOIN exercise_tag et ON t.id = et.tag_id
        WHERE et.exercise_id = :exerciseId
        ORDER BY t.name ASC
        """,
    )
    fun getTags(exerciseId: Long): Flow<List<TagEntity>>
}
