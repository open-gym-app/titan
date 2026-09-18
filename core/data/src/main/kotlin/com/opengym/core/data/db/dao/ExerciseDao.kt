package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.ExerciseTagEntity
import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.data.db.projection.CatalogueMuscleGroupLinkRow
import com.opengym.core.data.db.projection.ExerciseMuscleGroupLinkRow
import com.opengym.core.data.db.projection.MobilityExerciseRow
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
    suspend fun removeMuscleGroup(
        exerciseId: Long,
        muscleGroupId: Long,
    )

    @Query("DELETE FROM exercise_muscle_group WHERE exercise_id = :exerciseId")
    suspend fun clearMuscleGroups(exerciseId: Long)

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

    /**
     * Every link in the catalogue, in one result set, grouped by exercise and ordered
     * within each exercise exactly as [getMuscleGroupLinks] orders one.
     *
     * For the screens that draw the whole catalogue at once. Running the per-exercise query
     * once per exercise would mean 873 Room observers on the same two tables — one per
     * seeded row, before any custom ones — all invalidated together by any write to either.
     *
     * `ORDER BY exercise_id` first so the caller can group in one pass over an already
     * ordered result, rather than sorting a map's values afterwards.
     */
    @Query(
        """
        SELECT
            emg.exercise_id AS exercise_id,
            mg.id AS muscle_group_id,
            mg.name AS muscle_group_name,
            mg.icon AS muscle_group_icon,
            emg.is_primary AS is_primary
        FROM muscle_group mg
        INNER JOIN exercise_muscle_group emg ON mg.id = emg.muscle_group_id
        ORDER BY emg.exercise_id ASC, emg.is_primary DESC, mg.name ASC
        """,
    )
    fun getAllMuscleGroupLinks(): Flow<List<CatalogueMuscleGroupLinkRow>>

    /**
     * Every movement a warm-up or a cool-down can be built from (#214): the two mobility
     * disciplines, one row per prime mover, each saying whether it is tagged `DYNAMIC`.
     *
     * One query rather than three. The alternative is the catalogue, its links and its tags read
     * separately and combined in memory — three Flows over 873 exercises to arrive at the 184
     * rows this returns, re-run in full on any write to any of the three tables. The `discipline`
     * index does the filtering, and the `EXISTS` rides the `exercise_tag` primary key.
     *
     * Ordered by name and then by muscle group so the repository can collapse the rows in one
     * pass, and so the planner is handed a list whose order is the database's rather than one
     * that depends on how SQLite felt about the join today — its pick is a function of that list,
     * and a stable order is half of what makes the pick stable.
     *
     * @param disciplines the `discipline` column values to admit — `STRETCHING` and
     *   `PLYOMETRICS`. Passed in rather than written into the SQL because the enum's constant
     *   names belong to the domain, and a literal here is a second copy of them.
     * @param dynamicTag the name of the tag that marks a movement as dynamic.
     */
    @Query(
        """
        SELECT
            e.*,
            emg.muscle_group_id AS muscle_group_id,
            EXISTS (
                SELECT 1 FROM exercise_tag et
                INNER JOIN tag t ON t.id = et.tag_id
                WHERE et.exercise_id = e.id AND t.name = :dynamicTag
            ) AS is_dynamic
        FROM exercise e
        INNER JOIN exercise_muscle_group emg
            ON emg.exercise_id = e.id AND emg.is_primary = 1
        WHERE e.discipline IN (:disciplines)
        ORDER BY e.name ASC, emg.muscle_group_id ASC
        """,
    )
    fun getMobilityExercises(
        disciplines: List<String>,
        dynamicTag: String,
    ): Flow<List<MobilityExerciseRow>>

    /**
     * Write an exercise and its muscle links together, or write neither.
     *
     * The transaction is the point: a custom exercise whose links did not land is one that
     * trains nothing — absent from every muscle filter, and contributing nothing to volume
     * attribution — and the user has no way to tell from looking at it.
     *
     * [links] arrive with whatever `exerciseId` the caller had (they cannot know the
     * generated one), and are re-pointed at the row this actually inserted.
     */
    @Transaction
    suspend fun insertWithMuscleGroups(
        exercise: ExerciseEntity,
        links: List<ExerciseMuscleGroupEntity>,
    ): Long {
        val exerciseId = insert(exercise)
        links.forEach { link -> addMuscleGroup(link.copy(exerciseId = exerciseId)) }
        return exerciseId
    }

    /**
     * Rewrite an exercise and swap its muscle links for [links], or do neither.
     *
     * Clear-then-insert rather than a diff: the caller is handing over the whole answer to
     * "what does this work", and a diff would have this method deciding what it meant that a
     * group was left out. The transaction is what keeps the gap between the two writes
     * invisible — see [insertWithMuscleGroups] for why an exercise with no links is worse than
     * a failed write.
     *
     * [links] are re-pointed at [exercise]'s id, so a caller that built them from a form's
     * chip selection does not have to.
     */
    @Transaction
    suspend fun updateWithMuscleGroups(
        exercise: ExerciseEntity,
        links: List<ExerciseMuscleGroupEntity>,
    ) {
        update(exercise)
        clearMuscleGroups(exercise.id)
        links.forEach { link -> addMuscleGroup(link.copy(exerciseId = exercise.id)) }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTag(ref: ExerciseTagEntity)

    @Query("DELETE FROM exercise_tag WHERE exercise_id = :exerciseId AND tag_id = :tagId")
    suspend fun removeTag(
        exerciseId: Long,
        tagId: Long,
    )

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
