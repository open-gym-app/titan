package com.opengym.core.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference/catalog tables: muscle_group, tag, workout_template, template_exercise, body_weight_log. */
class CatalogDaoTest : RoomTestBase() {

    // --- muscle_group -----------------------------------------------------

    @Test
    fun `insertAll ignores duplicate names and lists ordered by name`() = runBlocking {
        val dao = db.muscleGroupDao()
        dao.insertAll(listOf(muscleGroup("QUADRICEPS"), muscleGroup("LATS")))
        dao.insertAll(listOf(muscleGroup("LATS"))) // duplicate -> ignored
        val names = dao.getAll().first().map { it.name }
        assertEquals(listOf("LATS", "QUADRICEPS"), names)
    }

    // --- tag --------------------------------------------------------------

    @Test
    fun `getSystemTags returns only system tags`() = runBlocking {
        val dao = db.tagDao()
        dao.insert(tag("PUSH", isSystem = true))
        dao.insert(tag("Favourites", isSystem = false))
        assertEquals(listOf("PUSH"), dao.getSystemTags().first().map { it.name })
    }

    @Test
    fun `deleteCustom removes a custom tag but leaves system tags`() = runBlocking {
        val dao = db.tagDao()
        val systemId = dao.insert(tag("PULL", isSystem = true))
        val customId = dao.insert(tag("Mine", isSystem = false))
        dao.deleteCustom(customId)
        dao.deleteCustom(systemId) // no-op: guarded by is_system = 0
        assertEquals(listOf("PULL"), dao.getAll().first().map { it.name })
    }

    // --- workout_template / template_exercise -----------------------------

    @Test
    fun `template exercises are ordered by position`() = runBlocking {
        val dao = db.workoutTemplateDao()
        val templateId = dao.insert(template())
        val exerciseId = db.exerciseDao().insert(exercise())
        dao.insertExercise(templateExercise(templateId, exerciseId, position = 2))
        dao.insertExercise(templateExercise(templateId, exerciseId, position = 0))
        dao.insertExercise(templateExercise(templateId, exerciseId, position = 1))
        val positions = dao.getExercises(templateId).first().map { it.position }
        assertEquals(listOf(0, 1, 2), positions)
    }

    @Test
    fun `a template's superset grouping survives the round trip`() = runBlocking {
        // The grouping is what a session inherits when the template is applied, so a
        // column that lost it would quietly ungroup every workout started from here.
        val dao = db.workoutTemplateDao()
        val templateId = dao.insert(template())
        val exerciseId = db.exerciseDao().insert(exercise())
        dao.insertExercise(templateExercise(templateId, exerciseId, position = 0))
        dao.insertExercise(templateExercise(templateId, exerciseId, position = 1, supersetGroup = 0))
        dao.insertExercise(templateExercise(templateId, exerciseId, position = 2, supersetGroup = 0))

        assertEquals(
            listOf(null, 0, 0),
            dao.getExercises(templateId).first().map { it.supersetGroup },
        )
    }

    @Test
    fun `a template's category survives the round trip, and no duration is stored beside it`() =
        runBlocking {
            val dao = db.workoutTemplateDao()
            val templateId = dao.insert(template(category = "HYPERTROPHY"))

            val stored = requireNotNull(dao.getById(templateId).first())

            assertEquals("HYPERTROPHY", stored.category)
            // The estimate is computed from the exercises by TemplateDuration; asserting
            // the row holds nothing to update is what keeps it that way, since a column
            // added here would go stale on the next edit.
            assertEquals(
                listOf("category", "icon", "id", "name"),
                db.query("SELECT * FROM workout_template LIMIT 0", emptyArray<Any?>())
                    .use { it.columnNames.sorted() },
            )
        }

    @Test
    fun `deleting a template cascades to its template_exercise rows`() = runBlocking {
        val dao = db.workoutTemplateDao()
        val templateId = dao.insert(template())
        val exerciseId = db.exerciseDao().insert(exercise())
        dao.insertExercise(templateExercise(templateId, exerciseId, position = 0))
        dao.delete(templateId)
        assertTrue(dao.getExercises(templateId).first().isEmpty())
    }

    // --- body_weight_log --------------------------------------------------

    @Test
    fun `body weight logs are listed newest first and latest returns the newest`() = runBlocking {
        val dao = db.bodyWeightLogDao()
        dao.insert(bodyWeightLog(weight = 80_000_000, createdAt = 100))
        dao.insert(bodyWeightLog(weight = 79_500_000, createdAt = 300))
        dao.insert(bodyWeightLog(weight = 79_800_000, createdAt = 200))
        assertEquals(listOf(300L, 200L, 100L), dao.getAll().first().map { it.createdAt })
        assertEquals(300L, dao.getLatest().first()?.createdAt)
    }

    @Test
    fun `getLatest is null when empty`() = runBlocking {
        assertNull(db.bodyWeightLogDao().getLatest().first())
    }
}
