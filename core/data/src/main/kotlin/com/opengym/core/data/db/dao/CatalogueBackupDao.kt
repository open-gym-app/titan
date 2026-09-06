package com.opengym.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.ExerciseTagEntity
import com.opengym.core.data.db.entity.MuscleGroupEntity
import com.opengym.core.data.db.entity.TagEntity

/**
 * Whole-table reads, inserts and deletes over the exercise library, for backup only.
 *
 * ### Why backup has DAOs of its own
 *
 * Every other DAO in this package answers a screen's question — the sessions of a week, the
 * sets of an exercise, a template with its body. They filter, join and order for a reader, and
 * several of them return projections rather than rows. An export needs the opposite: every row
 * of one table, columns untouched, in a stable order, a page at a time. Squeezing that into
 * `ExerciseDao` would put a query nothing on a screen wants beside the ones every screen does,
 * and would leave the export depending on a shape that exists to serve a list.
 *
 * ### Ordered by primary key, always
 *
 * Not for the reader's sake — nothing renders this — but so an export and the import that reads
 * it back produce the *same* order, which is what lets the round-trip test assert row-for-row
 * equality per table rather than set equality. The two join tables have no surrogate id, so
 * they order by their composite key.
 *
 * ### Deletes are `@Query`, not `clearAllTables`
 *
 * `RoomDatabase.clearAllTables` cannot be used here: it runs a `VACUUM` outside its own
 * transaction, and the import's wipe has to be *inside* the transaction that inserts, or a
 * failed restore leaves a device wiped with nothing left to restore from. A `@Query("DELETE
 * FROM …")` also keeps Room's invalidation tracker informed, which a raw `execSQL` would not —
 * a table emptied by an import and left empty would otherwise leave any live query over it
 * showing rows that are gone.
 */
interface CatalogueBackupDao {

    @Query("SELECT * FROM muscle_group ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readMuscleGroups(
        limit: Int,
        offset: Int,
    ): List<MuscleGroupEntity>

    @Insert
    suspend fun insertMuscleGroups(rows: List<MuscleGroupEntity>)

    @Query("DELETE FROM muscle_group")
    suspend fun deleteMuscleGroups()

    @Query("SELECT * FROM tag ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readTags(
        limit: Int,
        offset: Int,
    ): List<TagEntity>

    @Insert
    suspend fun insertTags(rows: List<TagEntity>)

    @Query("DELETE FROM tag")
    suspend fun deleteTags()

    @Query("SELECT * FROM exercise ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readExercises(
        limit: Int,
        offset: Int,
    ): List<ExerciseEntity>

    @Insert
    suspend fun insertExercises(rows: List<ExerciseEntity>)

    @Query("DELETE FROM exercise")
    suspend fun deleteExercises()

    @Query(
        "SELECT * FROM exercise_muscle_group ORDER BY exercise_id, muscle_group_id LIMIT :limit OFFSET :offset",
    )
    suspend fun readExerciseMuscleGroups(
        limit: Int,
        offset: Int,
    ): List<ExerciseMuscleGroupEntity>

    @Insert
    suspend fun insertExerciseMuscleGroups(rows: List<ExerciseMuscleGroupEntity>)

    @Query("DELETE FROM exercise_muscle_group")
    suspend fun deleteExerciseMuscleGroups()

    @Query("SELECT * FROM exercise_tag ORDER BY exercise_id, tag_id LIMIT :limit OFFSET :offset")
    suspend fun readExerciseTags(
        limit: Int,
        offset: Int,
    ): List<ExerciseTagEntity>

    @Insert
    suspend fun insertExerciseTags(rows: List<ExerciseTagEntity>)

    @Query("DELETE FROM exercise_tag")
    suspend fun deleteExerciseTags()
}
