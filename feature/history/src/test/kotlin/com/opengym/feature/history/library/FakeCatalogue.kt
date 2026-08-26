package com.opengym.feature.history.library

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.Tag
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory catalogue behind the two repositories the library reads.
 *
 * Hand-written rather than mocked, because the contract under test is *behaviour over time*:
 * half the assertions below are about the list re-emitting after an insert, and a mock
 * returning a fixed flow cannot express that. It honours the parts of the contract the screen
 * depends on — rows come back sorted by name, links come back primary-first, the search is
 * the same case-insensitive substring match SQLite's `LIKE` performs, and the unique index on
 * (name, equipment) is enforced.
 */
class FakeExerciseRepository(
    exercises: List<Exercise> = emptyList(),
    links: Map<Long, List<ExerciseMuscleGroupLink>> = emptyMap(),
    private val taxonomy: List<MuscleGroup> = emptyList(),
) : ExerciseRepository {

    private val exercises = MutableStateFlow(exercises.sortedBy { it.name })
    private val links = MutableStateFlow(links)

    /** When set, every insert throws it — the write the store refuses. */
    var insertFailure: Throwable? = null

    /** When set, every update throws it. The detail screen's two writes go through here. */
    var updateFailure: Throwable? = null

    /** When set, every delete throws it — a row the schema will not let go of. */
    var deleteFailure: Throwable? = null

    /**
     * When set, an insert waits on it before doing anything.
     *
     * A real write is slow enough for the user to act while it is in flight — dismissing the
     * sheet, most importantly — and an insert that resolves the instant it is called cannot
     * express that. Complete it to let the write finish.
     */
    var insertGate: CompletableDeferred<Unit>? = null

    /** The next id an insert hands out. Above any fixture's, so ids stay unique. */
    private var nextId = INSERT_ID_BASE

    /**
     * Whether `getAll()` has anything to say yet.
     *
     * The four reads behind the library are four separate queries that answer independently,
     * and the catalogue is not reliably the first — which is the whole reason the screen has
     * to tell "not loaded yet" apart from "loaded, and empty". A fake whose catalogue is
     * always instantly present cannot express the difference.
     */
    private val catalogueArrived = MutableStateFlow(true)

    /** Hold `getAll()` silent, as if the query had not come back yet. */
    fun withholdCatalogue() {
        catalogueArrived.value = false
    }

    /** Let the withheld `getAll()` answer. */
    fun releaseCatalogue() {
        catalogueArrived.value = true
    }

    override fun getAll(): Flow<List<Exercise>> =
        combine(exercises, catalogueArrived) { rows, arrived -> rows.takeIf { arrived } }
            .filterNotNull()

    override fun getById(id: Long): Flow<Exercise?> = exercises.map { all -> all.firstOrNull { it.id == id } }

    override fun getByType(type: ExerciseType): Flow<List<Exercise>> =
        exercises.map { all -> all.filter { it.type == type } }

    override fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>> =
        exercises.map { all -> all.filter { it.equipmentType == equipmentType } }

    override fun searchByPartialName(query: String): Flow<List<Exercise>> =
        exercises.map { all -> all.filter { it.name.contains(query, ignoreCase = true) } }

    override fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>> =
        links.map { it[exerciseId].orEmpty() }

    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> = links

    override fun getMuscleGroups(): Flow<List<MuscleGroup>> = MutableStateFlow(taxonomy)

    override fun getTags(exerciseId: Long): Flow<List<Tag>> = MutableStateFlow(emptyList())

    override suspend fun insert(exercise: Exercise): Long = insert(exercise, emptyList())

    override suspend fun insert(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ): Long {
        insertGate?.await()
        insertFailure?.let { throw it }
        val duplicate = exercises.value.any {
            it.name == exercise.name && it.equipmentType == exercise.equipmentType
        }
        require(!duplicate) { "an exercise with that name and equipment already exists" }

        val id = nextId++
        exercises.update { (it + exercise.copy(id = id)).sortedBy { row -> row.name } }
        if (muscleGroups.isNotEmpty()) {
            links.update { it + (id to muscleGroups.sortedByDescending { link -> link.isPrimary }) }
        }
        return id
    }

    override suspend fun update(exercise: Exercise) {
        updateFailure?.let { throw it }
        exercises.update { all -> all.map { if (it.id == exercise.id) exercise else it } }
    }

    override suspend fun update(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ) {
        update(exercise)
        // Replaced rather than merged, and dropped rather than stored empty — both are what
        // the contract says and what `getAllMuscleGroupLinks` depends on.
        links.update { stored ->
            if (muscleGroups.isEmpty()) {
                stored - exercise.id
            } else {
                stored + (exercise.id to muscleGroups.sortedByDescending { link -> link.isPrimary })
            }
        }
    }

    override suspend fun delete(id: Long) {
        deleteFailure?.let { throw it }
        exercises.update { all -> all.filterNot { it.id == id } }
        links.update { it - id }
    }

    private companion object {
        const val INSERT_ID_BASE = 1_000L
    }
}

/** The records, as a map the test writes directly. */
class FakePersonalRecordRepository(records: Map<Long, PersonalRecord> = emptyMap()) : PersonalRecordRepository {

    private val records = MutableStateFlow(records)

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = records
}

// ─── Fixtures ───────────────────────────────────────────────────────────────────────────

val CHEST = MuscleGroup(id = 1, name = "CHEST")
val LATS = MuscleGroup(id = 2, name = "LATS")
val TRICEPS = MuscleGroup(id = 3, name = "TRICEPS")
val TAXONOMY = listOf(CHEST, LATS, TRICEPS)

val BENCH = Exercise(id = 10, name = "Bench press", equipmentType = EquipmentType.BARBELL)
val FLY = Exercise(id = 11, name = "Cable fly", equipmentType = EquipmentType.CABLE)
val PULL_UP = Exercise(id = 12, name = "Pull-up", equipmentType = EquipmentType.BODYWEIGHT)

val CATALOGUE = listOf(BENCH, FLY, PULL_UP)

val CATALOGUE_LINKS = mapOf(
    BENCH.id to listOf(
        ExerciseMuscleGroupLink(CHEST, isPrimary = true),
        ExerciseMuscleGroupLink(TRICEPS, isPrimary = false),
    ),
    FLY.id to listOf(ExerciseMuscleGroupLink(CHEST, isPrimary = true)),
    PULL_UP.id to listOf(ExerciseMuscleGroupLink(LATS, isPrimary = true)),
)

val BENCH_RECORD = PersonalRecord(
    id = 1,
    exerciseId = BENCH.id,
    workoutSetId = 1,
    type = PrType.MAX_WEIGHT,
    weight = 92_000_000,
    achievedAt = 0,
)

fun catalogueRepository() =
    FakeExerciseRepository(
        exercises = CATALOGUE,
        links = CATALOGUE_LINKS,
        taxonomy = TAXONOMY,
    )
