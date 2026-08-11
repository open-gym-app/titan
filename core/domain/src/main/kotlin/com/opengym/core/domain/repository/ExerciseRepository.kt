package com.opengym.core.domain.repository

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface ExerciseRepository {
    fun getAll(): Flow<List<Exercise>>
    fun getById(id: Long): Flow<Exercise?>
    fun getByType(type: ExerciseType): Flow<List<Exercise>>
    fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>>
    fun searchByPartialName(query: String): Flow<List<Exercise>>

    /**
     * The muscle groups [exerciseId] trains, each carrying its `is_primary` flag.
     *
     * Returns links rather than bare [com.opengym.core.domain.model.MuscleGroup]s so a
     * caller can weight primary against secondary — what
     * [com.opengym.core.domain.stats.MuscleVolumeCalculator] needs — without a second
     * query for the flags.
     *
     * Ordered primary first, then by muscle group name, so a detail screen can render
     * the list as it arrives.
     */
    fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>>

    /**
     * Every exercise's muscle group links at once, keyed by exercise id.
     *
     * The bulk form of [getMuscleGroupLinks], for the screens that draw the *whole*
     * catalogue: the library groups 184 exercises by what they train and labels each row
     * with it, and doing that through the per-exercise query means 184 observed queries and
     * 184 Flows to combine. This is one.
     *
     * An exercise with no links has no entry rather than an empty one — the seed leaves a
     * handful unlinked, and a caller reading `links[id].orEmpty()` reads the same answer
     * either way while a caller iterating the map is not handed empty groups to draw.
     *
     * Each list carries the ordering [getMuscleGroupLinks] gives: primary movers first, then
     * by muscle group name. The first entry is therefore the exercise's prime mover, which
     * is what a grouped list sections on.
     */
    fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>>

    fun getTags(exerciseId: Long): Flow<List<Tag>>
    suspend fun insert(exercise: Exercise): Long

    /**
     * Insert [exercise] and link it to [muscleGroups] as one atomic write.
     *
     * What a user-authored exercise needs, and the reason it is not [insert] followed by a
     * loop: the exercise's row and the muscles it works are one fact, and a crash between
     * the two writes would leave a custom exercise in the catalogue that trains nothing —
     * invisible to the muscle filters, and contributing nothing to volume attribution.
     *
     * Only [ExerciseMuscleGroupLink.isPrimary] and the group's id are read; the rest of the
     * [com.opengym.core.domain.model.MuscleGroup] is the caller's copy of a row that already
     * exists. Linking to a group id that does not exist fails the write, as does a name and
     * equipment pair the catalogue already holds.
     *
     * @return the new exercise's id.
     */
    suspend fun insert(exercise: Exercise, muscleGroups: List<ExerciseMuscleGroupLink>): Long

    /** The muscle group taxonomy, ordered by name — the vocabulary a custom exercise picks from. */
    fun getMuscleGroups(): Flow<List<MuscleGroup>>
    suspend fun update(exercise: Exercise)
    suspend fun delete(id: Long)
}
