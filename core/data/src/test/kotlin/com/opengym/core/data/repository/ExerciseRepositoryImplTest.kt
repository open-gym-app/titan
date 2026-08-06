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
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
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
}
