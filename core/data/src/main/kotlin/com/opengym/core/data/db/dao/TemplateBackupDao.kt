package com.opengym.core.data.db.dao

import androidx.room.Insert
import androidx.room.Query
import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.TemplateSetEntity
import com.opengym.core.data.db.entity.WorkoutTemplateEntity

/** Whole-table reads, inserts and deletes over the three template tables. See [CatalogueBackupDao]. */
interface TemplateBackupDao {

    @Query("SELECT * FROM workout_template ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readTemplates(
        limit: Int,
        offset: Int,
    ): List<WorkoutTemplateEntity>

    @Insert
    suspend fun insertTemplates(rows: List<WorkoutTemplateEntity>)

    @Query("DELETE FROM workout_template")
    suspend fun deleteTemplates()

    @Query("SELECT * FROM template_exercise ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readTemplateExercises(
        limit: Int,
        offset: Int,
    ): List<TemplateExerciseEntity>

    @Insert
    suspend fun insertTemplateExercises(rows: List<TemplateExerciseEntity>)

    @Query("DELETE FROM template_exercise")
    suspend fun deleteTemplateExercises()

    @Query("SELECT * FROM template_set ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun readTemplateSets(
        limit: Int,
        offset: Int,
    ): List<TemplateSetEntity>

    @Insert
    suspend fun insertTemplateSets(rows: List<TemplateSetEntity>)

    @Query("DELETE FROM template_set")
    suspend fun deleteTemplateSets()
}
