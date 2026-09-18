package com.opengym.core.domain.repository

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MobilityExercise
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
     * catalogue: the library groups 873 seeded exercises by what they train and labels each
     * row with it, and doing that through the per-exercise query means 873 observed queries
     * and 873 Flows to combine. This is one.
     *
     * An exercise with no links has no entry rather than an empty one. Nothing produces one
     * today — the seed gives every exercise a prime mover, and the custom-exercise form
     * requires one — but the map is a join's output and not a promise that the join is
     * total: a caller reading `links[id].orEmpty()` reads the same answer either way, while
     * a caller iterating the map is not handed empty groups to draw.
     *
     * Each list carries the ordering [getMuscleGroupLinks] gives: primary movers first, then
     * by muscle group name. The first entry is therefore the exercise's prime mover, which
     * is what a grouped list sections on.
     */
    fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>>

    /**
     * The mobility half of the catalogue: every [com.opengym.core.domain.model.Discipline.STRETCHING]
     * and [com.opengym.core.domain.model.Discipline.PLYOMETRICS] movement, with its prime movers
     * and whether it is dynamic.
     *
     * The one read [com.opengym.core.domain.usecase.SuggestWarmupUseCase] makes of the catalogue,
     * and it is deliberately not three: the exercises, their primary links and the `DYNAMIC` tag
     * are one question — "what could this session warm up with" — and asking it as three Flows
     * would combine 873 rows, 2,574 links and 1,717 tag rows to arrive at the 184 that matter.
     *
     * Ordered by name, and an exercise appears once however many prime movers it has. An exercise
     * of one of those disciplines with no primary link at all is absent rather than empty, as it
     * is from [getAllMuscleGroupLinks]: with nothing to match a session against, it could never
     * be suggested.
     */
    fun getMobilityExercises(): Flow<List<MobilityExercise>>

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
    suspend fun insert(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ): Long

    /** The muscle group taxonomy, ordered by name — the vocabulary a custom exercise picks from. */
    fun getMuscleGroups(): Flow<List<MuscleGroup>>

    /**
     * Rewrite [exercise]'s own row, leaving its muscle links alone.
     *
     * The form of the write for the fields nothing else depends on — the detail screen saving
     * the user's [Exercise.notes] is the ordinary caller. Editing what an exercise *trains* is
     * the other overload's job, because that is the write volume attribution reads.
     */
    suspend fun update(exercise: Exercise)

    /**
     * Rewrite [exercise] **and replace its muscle links** with [muscleGroups], atomically.
     *
     * The update counterpart of [insert], and transactional for the same reason: the row and
     * the muscles it works are one fact. A crash between clearing the old links and writing
     * the new ones would leave an exercise training nothing — invisible to the muscle filters,
     * contributing nothing to volume attribution, and indistinguishable from a correct one to
     * anybody looking at it.
     *
     * The links are *replaced*, not merged. An edit form hands back the whole answer to "what
     * does this work", and diffing it here would mean this class deciding what the user meant
     * by leaving a group out.
     *
     * Only [ExerciseMuscleGroupLink.isPrimary] and the group's id are read, as in [insert].
     * Linking to a group id that does not exist fails the write, as does a name and equipment
     * pair another exercise already holds.
     */
    suspend fun update(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    )

    suspend fun delete(id: Long)
}
