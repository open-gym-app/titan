package com.opengym.core.data.db

import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.ExerciseTagEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseDaoTest : RoomTestBase() {

    private val dao by lazy { db.exerciseDao() }

    @Test
    fun `insert then getById returns the row`() = runBlocking {
        val id = dao.insert(exercise(name = "Squat"))
        val loaded = dao.getById(id).first()
        assertEquals("Squat", loaded?.name)
    }

    @Test
    fun `getAll is ordered by name ascending`() = runBlocking {
        dao.insert(exercise(name = "Zercher"))
        dao.insert(exercise(name = "Arnold Press"))
        dao.insert(exercise(name = "Muscle Up"))
        val names = dao.getAll().first().map { it.name }
        assertEquals(listOf("Arnold Press", "Muscle Up", "Zercher"), names)
    }

    @Test
    fun `update changes persisted values`() = runBlocking {
        val id = dao.insert(exercise(name = "Dip"))
        val original = dao.getById(id).first()!!
        dao.update(original.copy(name = "Weighted Dip"))
        assertEquals("Weighted Dip", dao.getById(id).first()?.name)
    }

    @Test
    fun `delete removes the row`() = runBlocking {
        val id = dao.insert(exercise())
        dao.delete(id)
        assertNull(dao.getById(id).first())
    }

    @Test
    fun `getByType filters by exercise_type`() = runBlocking {
        dao.insert(exercise(name = "Run", exerciseType = "ENDURANCE"))
        dao.insert(exercise(name = "Bench", exerciseType = "STRENGTH"))
        val endurance = dao.getByType("ENDURANCE").first()
        assertEquals(listOf("Run"), endurance.map { it.name })
    }

    @Test
    fun `getByEquipmentType filters by equipment_type`() = runBlocking {
        dao.insert(exercise(name = "Barbell Row", equipmentType = "BARBELL"))
        dao.insert(exercise(name = "Cable Row", equipmentType = "CABLE"))
        val cable = dao.getByEquipmentType("CABLE").first()
        assertEquals(listOf("Cable Row"), cable.map { it.name })
    }

    @Test
    fun `searchByPartialName matches a substring case-insensitively`() = runBlocking {
        dao.insert(exercise(name = "Incline Bench Press"))
        dao.insert(exercise(name = "Deadlift"))
        val hits = dao.searchByPartialName("bench").first()
        assertEquals(listOf("Incline Bench Press"), hits.map { it.name })
    }

    @Test
    fun `muscle groups are returned primary-first then by name`() = runBlocking {
        val exerciseId = dao.insert(exercise())
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup("CHEST"))).first()
        val triceps = db.muscleGroupDao().insertAll(listOf(muscleGroup("TRICEPS"))).first()
        val shoulders = db.muscleGroupDao().insertAll(listOf(muscleGroup("SHOULDERS"))).first()
        // triceps + shoulders are secondary, chest primary
        dao.addMuscleGroup(ExerciseMuscleGroupEntity(exerciseId, triceps, isPrimary = false))
        dao.addMuscleGroup(ExerciseMuscleGroupEntity(exerciseId, shoulders, isPrimary = false))
        dao.addMuscleGroup(ExerciseMuscleGroupEntity(exerciseId, chest, isPrimary = true))

        val ordered = dao.getMuscleGroups(exerciseId).first().map { it.name }
        assertEquals(listOf("CHEST", "SHOULDERS", "TRICEPS"), ordered)
    }

    @Test
    fun `removeMuscleGroup detaches the join row`() = runBlocking {
        val exerciseId = dao.insert(exercise())
        val chest = db.muscleGroupDao().insertAll(listOf(muscleGroup("CHEST"))).first()
        dao.addMuscleGroup(ExerciseMuscleGroupEntity(exerciseId, chest, isPrimary = true))
        dao.removeMuscleGroup(exerciseId, chest)
        assertTrue(dao.getMuscleGroups(exerciseId).first().isEmpty())
    }

    @Test
    fun `tags are returned ordered by name`() = runBlocking {
        val exerciseId = dao.insert(exercise())
        val push = db.tagDao().insert(tag("PUSH"))
        val compound = db.tagDao().insert(tag("COMPOUND"))
        dao.addTag(ExerciseTagEntity(exerciseId, push))
        dao.addTag(ExerciseTagEntity(exerciseId, compound))
        val names = dao.getTags(exerciseId).first().map { it.name }
        assertEquals(listOf("COMPOUND", "PUSH"), names)
    }

    @Test
    fun `removeTag detaches the join row`() = runBlocking {
        val exerciseId = dao.insert(exercise())
        val push = db.tagDao().insert(tag("PUSH"))
        dao.addTag(ExerciseTagEntity(exerciseId, push))
        dao.removeTag(exerciseId, push)
        assertTrue(dao.getTags(exerciseId).first().isEmpty())
    }
}
