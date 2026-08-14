package com.opengym.core.data.repository

import com.opengym.core.data.db.RoomTestBase
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.ExerciseTagEntity
import com.opengym.core.data.db.exercise
import com.opengym.core.data.db.muscleGroup
import com.opengym.core.data.db.tag
import com.opengym.core.data.mapper.ExerciseMapper
import com.opengym.core.data.mapper.MuscleGroupLinkMapper
import com.opengym.core.data.mapper.TagMapper
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import com.opengym.core.domain.model.MuscleGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExerciseRepositoryImpl] against a real (Robolectric) SQLite, built by
 * [RoomTestBase] — the repository is a thin layer over queries whose ordering and
 * filtering are the behaviour under test, so a mocked DAO would only assert that
 * the class calls the method it obviously calls.
 */
class ExerciseRepositoryImplTest : RoomTestBase() {

    private val repository by lazy {
        ExerciseRepositoryImpl(
            exerciseDao = db.exerciseDao(),
            muscleGroupDao = db.muscleGroupDao(),
            exerciseMapper = ExerciseMapper(),
            tagMapper = TagMapper(),
            muscleGroupLinkMapper = MuscleGroupLinkMapper(),
        )
    }

    @Test
    fun `insert then getById returns the exercise as a domain model`() = runBlocking {
        val id = repository.insert(
            Exercise(
                name = "Front Squat",
                type = ExerciseType.STRENGTH,
                equipmentType = EquipmentType.BARBELL,
                mechanicType = MechanicType.COMPOUND,
                isCustom = true,
            ),
        )

        val loaded = repository.getById(id).first()

        assertEquals("Front Squat", loaded?.name)
        assertEquals(EquipmentType.BARBELL, loaded?.equipmentType)
        assertEquals(MechanicType.COMPOUND, loaded?.mechanicType)
        assertEquals(true, loaded?.isCustom)
        assertEquals(id, loaded?.id)
    }

    @Test
    fun `getById emits null for an id that was never stored`() = runBlocking {
        assertNull(repository.getById(404).first())
    }

    @Test
    fun `getAll is ordered by name`() = runBlocking {
        db.exerciseDao().insert(exercise(name = "Zercher Squat"))
        db.exerciseDao().insert(exercise(name = "Arnold Press", equipmentType = "DUMBBELL"))

        assertEquals(listOf("Arnold Press", "Zercher Squat"), repository.getAll().first().map { it.name })
    }

    @Test
    fun `getByType filters on the stored constant name`() = runBlocking {
        db.exerciseDao().insert(exercise(name = "Row", exerciseType = "ENDURANCE"))
        db.exerciseDao().insert(exercise(name = "Bench", exerciseType = "STRENGTH"))

        val endurance = repository.getByType(ExerciseType.ENDURANCE).first()

        assertEquals(listOf("Row"), endurance.map { it.name })
        assertEquals(ExerciseType.ENDURANCE, endurance.single().type)
    }

    @Test
    fun `getByEquipmentType filters on the stored constant name`() = runBlocking {
        db.exerciseDao().insert(exercise(name = "Cable Fly", equipmentType = "CABLE"))
        db.exerciseDao().insert(exercise(name = "Barbell Row", equipmentType = "BARBELL"))

        val cable = repository.getByEquipmentType(EquipmentType.CABLE).first()

        assertEquals(listOf("Cable Fly"), cable.map { it.name })
    }

    @Test
    fun `searchByPartialName matches anywhere in the name`() = runBlocking {
        db.exerciseDao().insert(exercise(name = "Bulgarian Split Squat"))
        db.exerciseDao().insert(exercise(name = "Back Squat"))
        db.exerciseDao().insert(exercise(name = "Bench Press"))

        val hits = repository.searchByPartialName("Squat").first().map { it.name }

        assertEquals(listOf("Back Squat", "Bulgarian Split Squat"), hits)
    }

    @Test
    fun `getMuscleGroupLinks returns primaries first with their flag`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise(name = "Bench Press"))
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST"))).single()
        val triceps = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "TRICEPS"))).single()
        db.exerciseDao().addMuscleGroup(ExerciseMuscleGroupEntity(exerciseId, triceps, isPrimary = false))
        db.exerciseDao().addMuscleGroup(ExerciseMuscleGroupEntity(exerciseId, chest, isPrimary = true))

        val links = repository.getMuscleGroupLinks(exerciseId).first()

        assertEquals(listOf("CHEST", "TRICEPS"), links.map { it.muscleGroup.name })
        assertEquals(listOf(true, false), links.map { it.isPrimary })
    }

    @Test
    fun `getMuscleGroupLinks is empty for an exercise with no links`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())

        assertTrue(repository.getMuscleGroupLinks(exerciseId).first().isEmpty())
    }

    @Test
    fun `getTags returns the exercise's tags ordered by name`() = runBlocking {
        val exerciseId = db.exerciseDao().insert(exercise())
        val push = db.tagDao().insert(tag(name = "PUSH", isSystem = true))
        val compound = db.tagDao().insert(tag(name = "COMPOUND"))
        db.tagDao().insert(tag(name = "UNRELATED"))
        db.exerciseDao().addTag(ExerciseTagEntity(exerciseId, push))
        db.exerciseDao().addTag(ExerciseTagEntity(exerciseId, compound))

        val tags = repository.getTags(exerciseId).first()

        assertEquals(listOf("COMPOUND", "PUSH"), tags.map { it.name })
        assertEquals(listOf(false, true), tags.map { it.isSystem })
    }

    @Test
    fun `update writes the changed fields back`() = runBlocking {
        val id = repository.insert(Exercise(name = "Dip"))
        val stored = repository.getById(id).first()!!

        repository.update(stored.copy(name = "Weighted Dip", defaultRest = 150))

        val reloaded = repository.getById(id).first()
        assertEquals("Weighted Dip", reloaded?.name)
        assertEquals(150L, reloaded?.defaultRest)
    }

    @Test
    fun `delete removes the exercise`() = runBlocking {
        val id = repository.insert(Exercise(name = "Sled Push"))

        repository.delete(id)

        assertNull(repository.getById(id).first())
        assertTrue(repository.getAll().first().isEmpty())
    }

    @Test
    fun `getAllMuscleGroupLinks keys every exercise's links, prime mover first`() = runBlocking {
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST"))).single()
        val triceps = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "TRICEPS"))).single()
        val bench = repository.insert(Exercise(name = "Bench Press"))
        val fly = repository.insert(Exercise(name = "Cable Fly", equipmentType = EquipmentType.CABLE))
        // Inserted secondary-first, so an ordering that merely echoed insertion order would
        // put the triceps at the head of the bench press.
        db.exerciseDao().addMuscleGroup(ExerciseMuscleGroupEntity(bench, triceps, isPrimary = false))
        db.exerciseDao().addMuscleGroup(ExerciseMuscleGroupEntity(bench, chest, isPrimary = true))
        db.exerciseDao().addMuscleGroup(ExerciseMuscleGroupEntity(fly, chest, isPrimary = true))

        val links = repository.getAllMuscleGroupLinks().first()

        assertEquals(setOf(bench, fly), links.keys)
        assertEquals(listOf("CHEST", "TRICEPS"), links.getValue(bench).map { it.muscleGroup.name })
        assertEquals(listOf(true, false), links.getValue(bench).map { it.isPrimary })
        assertEquals(listOf("CHEST"), links.getValue(fly).map { it.muscleGroup.name })
    }

    @Test
    fun `getAllMuscleGroupLinks has no entry for an exercise that trains nothing`() = runBlocking {
        repository.insert(Exercise(name = "Farmer's Carry"))

        assertTrue(repository.getAllMuscleGroupLinks().first().isEmpty())
    }

    @Test
    fun `insert with muscle groups writes the exercise and its links together`() = runBlocking {
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST"))).single()
        val triceps = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "TRICEPS"))).single()

        val id = repository.insert(
            exercise = Exercise(name = "Hammer Incline Press", isCustom = true),
            muscleGroups = listOf(
                ExerciseMuscleGroupLink(MuscleGroup(id = chest, name = "CHEST"), isPrimary = true),
                ExerciseMuscleGroupLink(MuscleGroup(id = triceps, name = "TRICEPS"), isPrimary = false),
            ),
        )

        assertEquals("Hammer Incline Press", repository.getById(id).first()?.name)
        val links = repository.getMuscleGroupLinks(id).first()
        assertEquals(listOf("CHEST", "TRICEPS"), links.map { it.muscleGroup.name })
        assertEquals(listOf(true, false), links.map { it.isPrimary })
    }

    @Test
    fun `insert with muscle groups writes nothing when a link cannot be satisfied`() = runBlocking {
        val failed = runCatching {
            repository.insert(
                exercise = Exercise(name = "Ghost Press"),
                muscleGroups = listOf(
                    ExerciseMuscleGroupLink(MuscleGroup(id = 404, name = "NOWHERE"), isPrimary = true),
                ),
            )
        }

        assertTrue(failed.isFailure)
        // The point of the transaction: an exercise that trains nothing would be invisible to
        // every muscle filter, and the user would have no way to tell from looking at it.
        assertTrue(repository.getAll().first().isEmpty())
    }

    @Test
    fun `update with muscle groups replaces the links rather than adding to them`() = runBlocking {
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST"))).single()
        val triceps = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "TRICEPS"))).single()
        val lats = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "LATS"))).single()

        val id = repository.insert(
            exercise = Exercise(name = "Hammer Incline Press", isCustom = true),
            muscleGroups = listOf(
                ExerciseMuscleGroupLink(MuscleGroup(id = chest, name = "CHEST"), isPrimary = true),
                ExerciseMuscleGroupLink(MuscleGroup(id = triceps, name = "TRICEPS"), isPrimary = false),
            ),
        )

        repository.update(
            exercise = requireNotNull(repository.getById(id).first()).copy(name = "Hammer Press"),
            muscleGroups = listOf(
                ExerciseMuscleGroupLink(MuscleGroup(id = lats, name = "LATS"), isPrimary = true),
            ),
        )

        assertEquals("Hammer Press", repository.getById(id).first()?.name)
        // The triceps link is *gone*, not demoted: the caller handed over the whole answer to
        // "what does this work", and a merge would keep a muscle they took off the form.
        val links = repository.getMuscleGroupLinks(id).first()
        assertEquals(listOf("LATS"), links.map { it.muscleGroup.name })
        assertEquals(listOf(true), links.map { it.isPrimary })
    }

    @Test
    fun `update with muscle groups can clear every link`() = runBlocking {
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST"))).single()
        val id = repository.insert(
            exercise = Exercise(name = "Hammer Incline Press", isCustom = true),
            muscleGroups = listOf(
                ExerciseMuscleGroupLink(MuscleGroup(id = chest, name = "CHEST"), isPrimary = true),
            ),
        )

        repository.update(requireNotNull(repository.getById(id).first()), muscleGroups = emptyList())

        assertTrue(repository.getMuscleGroupLinks(id).first().isEmpty())
        // No entry at all rather than an empty one, which is what the bulk read promises.
        assertTrue(repository.getAllMuscleGroupLinks().first().isEmpty())
    }

    @Test
    fun `update with muscle groups keeps the old links when a new one cannot be satisfied`() = runBlocking {
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST"))).single()
        val id = repository.insert(
            exercise = Exercise(name = "Hammer Incline Press", isCustom = true),
            muscleGroups = listOf(
                ExerciseMuscleGroupLink(MuscleGroup(id = chest, name = "CHEST"), isPrimary = true),
            ),
        )

        val failed = runCatching {
            repository.update(
                exercise = requireNotNull(repository.getById(id).first()).copy(name = "Ghost Press"),
                muscleGroups = listOf(
                    ExerciseMuscleGroupLink(MuscleGroup(id = 404, name = "NOWHERE"), isPrimary = true),
                ),
            )
        }

        assertTrue(failed.isFailure)
        // The transaction's whole point on the update path: the clear is rolled back with the
        // insert that failed, so the exercise still trains what it did before.
        assertEquals("Hammer Incline Press", repository.getById(id).first()?.name)
        assertEquals(listOf("CHEST"), repository.getMuscleGroupLinks(id).first().map { it.muscleGroup.name })
    }

    @Test
    fun `getMuscleGroups returns the taxonomy by name`() = runBlocking {
        db.muscleGroupDao().insertAll(
            listOf(muscleGroup(name = "TRICEPS"), muscleGroup(name = "CHEST")),
        )

        assertEquals(listOf("CHEST", "TRICEPS"), repository.getMuscleGroups().first().map { it.name })
    }
}
