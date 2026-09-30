package com.titan.core.data.repository

import com.titan.core.data.db.dao.WorkoutTemplateDao
import com.titan.core.data.mapper.TemplateExerciseMapper
import com.titan.core.data.mapper.TemplateMapper
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Saved exercise sequences, over [WorkoutTemplateDao].
 *
 * The writes are the interesting half: a template and its exercises are one
 * editable unit, so both go down as a single DAO transaction rather than a template
 * call followed by a loop. Numbering the exercises is the mapper's job and applying
 * them atomically is the DAO's; what is left here is deciding which id they are
 * written under.
 */
class TemplateRepositoryImpl @Inject constructor(
    private val templateDao: WorkoutTemplateDao,
    private val templateMapper: TemplateMapper,
    private val templateExerciseMapper: TemplateExerciseMapper,
) : TemplateRepository {

    override fun getAll(): Flow<List<WorkoutTemplate>> =
        templateDao.getAll().map { rows -> rows.map(templateMapper::toDomain) }

    override fun getById(id: Long): Flow<WorkoutTemplate?> =
        templateDao.getById(id).map { row -> row?.let(templateMapper::toDomain) }

    override fun getExercises(templateId: Long): Flow<List<TemplateExercise>> =
        templateDao.getExercises(templateId).map { rows -> rows.map(templateExerciseMapper::toDomain) }

    override fun getAllExercises(): Flow<List<TemplateExercise>> =
        templateDao.getAllExercises().map { rows -> rows.map(templateExerciseMapper::toDomain) }

    override suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long =
        templateDao.insertWithExercises(
            // id = 0 is what makes Room's autoGenerate assign one, and the contract says
            // any id the caller supplied is ignored — an insert that honoured it would
            // overwrite whichever template already held it.
            template = templateMapper.toEntity(template.copy(id = 0)),
            exercises = templateExerciseMapper.toEntities(templateId = 0, drafts = exercises),
        )

    override suspend fun update(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ) = templateDao.updateWithExercises(
        template = templateMapper.toEntity(template),
        exercises = templateExerciseMapper.toEntities(templateId = template.id, drafts = exercises),
    )

    override suspend fun delete(id: Long) = templateDao.delete(id)
}
