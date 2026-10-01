package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.dao.MuscleGroupDao
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.mapper.ExerciseMapper
import com.opengym.core.data.mapper.MuscleGroupLinkMapper
import com.opengym.core.data.mapper.TagMapper
import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MobilityExercise
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.Tag
import com.opengym.core.domain.repository.ExerciseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The catalogue, over [ExerciseDao].
 *
 * Every read is the DAO's Flow with the mapper applied, which keeps Room's
 * invalidation intact: the query re-runs on a write to `exercise` and the mapped
 * Flow re-emits with it. Ordering is the DAO's, not this class's — the SQL already
 * sorts by name, and re-sorting here would be a second pass over rows SQLite has
 * already ordered with an index.
 *
 * The enum-valued filters ([getByType], [getByEquipmentType]) are passed down as
 * their constant names, which is how the columns store them; see the mapper.
 *
 * [MuscleGroupDao] is here rather than behind a repository of its own because the taxonomy is
 * not a thing anybody browses — it is the vocabulary an exercise's muscle links are drawn
 * from, so its two callers ([getMuscleGroups] and the muscle filter it feeds) are both
 * questions about the catalogue.
 */
class ExerciseRepositoryImpl @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val muscleGroupDao: MuscleGroupDao,
    private val exerciseMapper: ExerciseMapper,
    private val tagMapper: TagMapper,
    private val muscleGroupLinkMapper: MuscleGroupLinkMapper,
) : ExerciseRepository {

    override fun getAll(): Flow<List<Exercise>> =
        exerciseDao.getAll().map { rows -> rows.map(exerciseMapper::toDomain) }

    override fun getById(id: Long): Flow<Exercise?> =
        exerciseDao.getById(id).map { row -> row?.let(exerciseMapper::toDomain) }

    override fun getByType(type: ExerciseType): Flow<List<Exercise>> =
        exerciseDao.getByType(type.name).map { rows -> rows.map(exerciseMapper::toDomain) }

    override fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>> =
        exerciseDao.getByEquipmentType(equipmentType.name).map { rows -> rows.map(exerciseMapper::toDomain) }

    override fun searchByPartialName(query: String): Flow<List<Exercise>> =
        exerciseDao.searchByPartialName(query).map { rows -> rows.map(exerciseMapper::toDomain) }

    override fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>> =
        exerciseDao.getMuscleGroupLinks(exerciseId).map { rows -> rows.map(muscleGroupLinkMapper::toDomain) }

    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> =
        exerciseDao.getAllMuscleGroupLinks().map { rows ->
            // `groupBy` preserves encounter order within each group, and the query orders by
            // exercise then primary-first — so each list arrives with its prime mover at the
            // head without a second sort here.
            rows.groupBy(
                keySelector = { it.exerciseId },
                valueTransform = { muscleGroupLinkMapper.toDomain(it.link) },
            )
        }

    override fun getMobilityExercises(): Flow<List<MobilityExercise>> =
        exerciseDao.getMobilityExercises(
            disciplines = MOBILITY_DISCIPLINES.map(Discipline::name),
            dynamicTag = MobilityExercise.DYNAMIC_TAG,
        ).map { rows ->
            // One pass over rows the query has already ordered by exercise name, so an exercise
            // with two prime movers arrives as two adjacent rows and collapses without a sort.
            // `isDynamic` is the same value on every row of one exercise — it is an `EXISTS` over
            // that exercise — so taking the first is taking the only one there is.
            rows.groupBy { it.exercise.id }
                .map { (_, sameExercise) ->
                    MobilityExercise(
                        exercise = exerciseMapper.toDomain(sameExercise.first().exercise),
                        primaryMuscleGroupIds = sameExercise.mapTo(mutableSetOf()) { it.muscleGroupId },
                        isDynamic = sameExercise.first().isDynamic,
                    )
                }
        }

    override fun getMuscleGroups(): Flow<List<MuscleGroup>> =
        muscleGroupDao.getAll().map { rows -> rows.map(muscleGroupLinkMapper::toDomain) }

    override fun getTags(exerciseId: Long): Flow<List<Tag>> =
        exerciseDao.getTags(exerciseId).map { rows -> rows.map(tagMapper::toDomain) }

    override suspend fun insert(exercise: Exercise): Long = exerciseDao.insert(exerciseMapper.toEntity(exercise))

    override suspend fun insert(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ): Long =
        exerciseDao.insertWithMuscleGroups(
            exercise = exerciseMapper.toEntity(exercise),
            // The exercise id is a placeholder: the row does not exist yet, so there is
            // nothing truthful to put here. The DAO overwrites it with the generated one.
            links = muscleGroups.map(::linkEntity),
        )

    override suspend fun update(exercise: Exercise) = exerciseDao.update(exerciseMapper.toEntity(exercise))

    override suspend fun update(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ) = exerciseDao.updateWithMuscleGroups(
        exercise = exerciseMapper.toEntity(exercise),
        links = muscleGroups.map(::linkEntity),
    )

    override suspend fun delete(id: Long) = exerciseDao.delete(id)

    /**
     * A domain link as a join row, with the exercise id left for the DAO to fill in.
     *
     * Both writes that take links hand the DAO rows it re-points itself — an insert has no id
     * yet, and an update has one the caller has already put on the exercise — so neither
     * caller has anything truthful to put here.
     */
    private fun linkEntity(link: ExerciseMuscleGroupLink) =
        ExerciseMuscleGroupEntity(
            exerciseId = 0,
            muscleGroupId = link.muscleGroup.id,
            isPrimary = link.isPrimary,
        )

    private companion object {

        /**
         * The two disciplines a warm-up or a cool-down is built from.
         *
         * Here rather than in the DAO because they are domain constants: the `discipline` column
         * stores [Discipline] by name, and the SQL should not be the place that spells them.
         */
        val MOBILITY_DISCIPLINES = listOf(Discipline.STRETCHING, Discipline.PLYOMETRICS)
    }
}
