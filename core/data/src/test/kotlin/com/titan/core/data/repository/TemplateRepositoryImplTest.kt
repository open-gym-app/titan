package com.titan.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.exercise
import com.titan.core.data.db.session
import com.titan.core.data.db.sessionExercise
import com.titan.core.data.mapper.TemplateExerciseMapper
import com.titan.core.data.mapper.TemplateMapper
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutTemplate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateRepositoryImplTest : RoomTestBase() {

    private val repository by lazy {
        TemplateRepositoryImpl(
            templateDao = db.workoutTemplateDao(),
            templateMapper = TemplateMapper(),
            templateExerciseMapper = TemplateExerciseMapper(),
        )
    }

    /** A catalogue exercise to build templates out of, named so ids stay distinct. */
    private suspend fun catalogueExercise(name: String): Long = db.exerciseDao().insert(exercise(name = name))

    @Test
    fun `insert stores the template and its exercises together`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val bench = catalogueExercise("Bench")

            val id = repository.insert(
                WorkoutTemplate(name = "Push Day", icon = "dumbbell"),
                listOf(
                    TemplateExerciseDraft(exerciseId = squat, sets = List(5) { PlannedSet(reps = 5) }),
                    TemplateExerciseDraft(exerciseId = bench, notes = "pause"),
                ),
            )

            val template = repository.getById(id).first()
            val exercises = repository.getExercises(id).first()
            assertEquals("Push Day", template?.name)
            assertEquals("dumbbell", template?.icon)
            assertEquals(listOf(squat, bench), exercises.map { it.exerciseId })
            assertEquals(listOf(5, 0), exercises.map { it.sets.size })
            assertEquals(listOf(null, "pause"), exercises.map { it.notes })
            assertTrue(exercises.all { it.templateId == id })
        }

    @Test
    fun `a plan survives the round trip set for set`() =
        runBlocking {
            // The whole point of the table: a warm-up, two working sets and a drop set have to come
            // back as four different sets rather than as "four sets of something".
            val bench = catalogueExercise("Bench")
            val plan = listOf(
                PlannedSet(setType = SetType.WARMUP, reps = 12, weight = 40_000_000),
                PlannedSet(reps = 5, weight = 80_000_000),
                PlannedSet(reps = 5, weight = 85_000_000),
                PlannedSet(setType = SetType.DROP_SET, reps = 10, weight = 50_000_000),
            )

            val id = repository.insert(
                WorkoutTemplate(name = "Bench day"),
                listOf(TemplateExerciseDraft(exerciseId = bench, sets = plan)),
            )

            assertEquals(plan, repository.getExercises(id).first().single().sets)
        }

    @Test
    fun `a timed plan survives the round trip with its times and distances`() =
        runBlocking {
            // `3 × 0:45` of a plank and a `5 km` run (#M7-17), written into `template_set` and
            // read back as the same plan.
            val plank = catalogueExercise("Plank")
            val run = catalogueExercise("Running, Outdoor")
            val holds = List(3) { PlannedSet(duration = 45) }
            val runs = listOf(PlannedSet(duration = 1_800, distance = 500_000))

            val id = repository.insert(
                WorkoutTemplate(name = "Core and a run"),
                listOf(
                    TemplateExerciseDraft(exerciseId = plank, sets = holds),
                    TemplateExerciseDraft(exerciseId = run, sets = runs),
                ),
            )

            assertEquals(listOf(holds, runs), repository.getExercises(id).first().map { it.sets })
        }

    @Test
    fun `replacing a template replaces the sets its exercises planned`() =
        runBlocking {
            val bench = catalogueExercise("Bench")
            val id = repository.insert(
                WorkoutTemplate(name = "Bench day"),
                listOf(TemplateExerciseDraft(exerciseId = bench, sets = List(5) { PlannedSet(reps = 5) })),
            )

            repository.update(
                WorkoutTemplate(id = id, name = "Bench day"),
                listOf(TemplateExerciseDraft(exerciseId = bench, sets = List(2) { PlannedSet(reps = 10) })),
            )

            // Two, not seven: the old rows go with the exercise they hung off, which cascades.
            val stored = repository.getExercises(id).first().single()
            assertEquals(2, stored.sets.size)
            assertEquals(listOf(10, 10), stored.sets.map(PlannedSet::reps))
        }

    @Test
    fun `deleting a template takes the sets its exercises planned with it`() =
        runBlocking {
            val bench = catalogueExercise("Bench")
            val id = repository.insert(
                WorkoutTemplate(name = "Bench day"),
                listOf(TemplateExerciseDraft(exerciseId = bench, sets = List(3) { PlannedSet() })),
            )

            repository.delete(id)

            assertTrue(repository.getExercises(id).first().isEmpty())
            assertTrue(repository.getAllExercises().first().isEmpty())
        }

    @Test
    fun `insert numbers positions from the order of the list, not from the caller`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val bench = catalogueExercise("Bench")
            val row = catalogueExercise("Row")

            val id = repository.insert(
                WorkoutTemplate(name = "Full Body"),
                listOf(row, squat, bench).map { TemplateExerciseDraft(exerciseId = it) },
            )

            val exercises = repository.getExercises(id).first()
            assertEquals(listOf(0, 1, 2), exercises.map { it.position })
            assertEquals(listOf(row, squat, bench), exercises.map { it.exerciseId })
        }

    @Test
    fun `insert ignores an id supplied by the caller`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val occupied = repository.insert(WorkoutTemplate(name = "Existing"), listOf(TemplateExerciseDraft(squat)))

            val id = repository.insert(
                WorkoutTemplate(id = occupied, name = "Impostor"),
                listOf(TemplateExerciseDraft(exerciseId = squat)),
            )

            assertNotEquals(occupied, id)
            assertEquals("Existing", repository.getById(occupied).first()?.name)
            assertEquals("Impostor", repository.getById(id).first()?.name)
        }

    @Test
    fun `insert accepts a template with no exercises`() =
        runBlocking {
            // The rule that a template needs one is CreateTemplateUseCase's, not the
            // store's; the store must not be the thing that enforces it twice.
            val id = repository.insert(WorkoutTemplate(name = "Empty"), emptyList())

            assertEquals("Empty", repository.getById(id).first()?.name)
            assertTrue(repository.getExercises(id).first().isEmpty())
        }

    @Test
    fun `insert writes nothing at all when an exercise row is rejected`() =
        runBlocking {
            val squat = catalogueExercise("Squat")

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    repository.insert(
                        WorkoutTemplate(name = "Half Written"),
                        listOf(TemplateExerciseDraft(exerciseId = squat), TemplateExerciseDraft(exerciseId = 9_999)),
                    )
                }
            }

            // The whole point of the single transaction: no named template with a
            // truncated body survives the failure.
            assertEquals(0, countRows("workout_template"))
            assertEquals(0, countRows("template_exercise"))
        }

    @Test
    fun `getAll is ordered by name`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            repository.insert(WorkoutTemplate(name = "Pull Day"), listOf(TemplateExerciseDraft(squat)))
            repository.insert(WorkoutTemplate(name = "Leg Day"), listOf(TemplateExerciseDraft(squat)))

            assertEquals(listOf("Leg Day", "Pull Day"), repository.getAll().first().map { it.name })
        }

    @Test
    fun `getById emits null for an id that was never stored`() =
        runBlocking {
            assertNull(repository.getById(404).first())
        }

    @Test
    fun `getExercises is empty for a template that does not exist`() =
        runBlocking {
            assertTrue(repository.getExercises(404).first().isEmpty())
        }

    @Test
    fun `update replaces the whole exercise list and renumbers it`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val bench = catalogueExercise("Bench")
            val row = catalogueExercise("Row")
            val id = repository.insert(
                WorkoutTemplate(name = "Push Day"),
                listOf(squat, bench, row).map { TemplateExerciseDraft(exerciseId = it) },
            )

            repository.update(
                WorkoutTemplate(id = id, name = "Push Day v2", icon = "barbell"),
                listOf(TemplateExerciseDraft(exerciseId = row), TemplateExerciseDraft(exerciseId = squat)),
            )

            val template = repository.getById(id).first()
            val exercises = repository.getExercises(id).first()
            assertEquals("Push Day v2", template?.name)
            assertEquals("barbell", template?.icon)
            assertEquals(listOf(row, squat), exercises.map { it.exerciseId })
            assertEquals(listOf(0, 1), exercises.map { it.position })
            assertEquals(2, countRows("template_exercise"))
        }

    @Test
    fun `update clears the exercise list when handed an empty one`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val id = repository.insert(WorkoutTemplate(name = "Push Day"), listOf(TemplateExerciseDraft(squat)))

            repository.update(WorkoutTemplate(id = id, name = "Push Day"), emptyList())

            assertTrue(repository.getExercises(id).first().isEmpty())
        }

    @Test
    fun `update is a no-op for a template that was never stored`() =
        runBlocking {
            val squat = catalogueExercise("Squat")

            // Both exercise lists, because without the guard in the DAO they diverge:
            // the empty one succeeds silently and the populated one fails the foreign
            // key on a template_id with no parent row. A caller cannot be asked to
            // predict which, so both are the no-op that `delete` already is.
            repository.update(WorkoutTemplate(id = 404, name = "Ghost"), emptyList())
            repository.update(WorkoutTemplate(id = 404, name = "Ghost"), listOf(TemplateExerciseDraft(squat)))

            assertNull(repository.getById(404).first())
            assertEquals(0, countRows("workout_template"))
            assertEquals(0, countRows("template_exercise"))
        }

    @Test
    fun `update on an absent template leaves the templates that do exist alone`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val id = repository.insert(WorkoutTemplate(name = "Push Day"), listOf(TemplateExerciseDraft(squat)))

            repository.update(WorkoutTemplate(id = 404, name = "Ghost"), listOf(TemplateExerciseDraft(squat)))

            assertEquals("Push Day", repository.getById(id).first()?.name)
            assertEquals(1, repository.getExercises(id).first().size)
            assertEquals(1, countRows("workout_template"))
        }

    @Test
    fun `update detaches past sessions from the rows it replaces without deleting them`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val id = repository.insert(WorkoutTemplate(name = "Push Day"), listOf(TemplateExerciseDraft(squat)))
            val templateExerciseId = repository.getExercises(id).first().single().id
            val sessionId = db.workoutSessionDao().insert(session(templateId = id, isInProgress = false))
            db.sessionExerciseDao().insert(sessionExercise(sessionId, squat, position = 0, templateExerciseId))

            repository.update(WorkoutTemplate(id = id, name = "Push Day"), listOf(TemplateExerciseDraft(squat)))

            // ON DELETE SET NULL: the session keeps its exercise, loses only the link
            // back to the template line it came from. Documented on TemplateRepository.
            val stored = db.sessionExerciseDao().getBySession(sessionId).first().single()
            assertNull(stored.templateExerciseId)
            assertEquals(squat, stored.exerciseId)
        }

    @Test
    fun `delete removes the template and cascades to its exercises`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val bench = catalogueExercise("Bench")
            val id = repository.insert(
                WorkoutTemplate(name = "Push Day"),
                listOf(squat, bench).map { TemplateExerciseDraft(exerciseId = it) },
            )

            repository.delete(id)

            assertNull(repository.getById(id).first())
            assertEquals(0, countRows("template_exercise"))
        }

    @Test
    fun `getAllExercises returns every template's exercises, grouped and in position order`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val bench = catalogueExercise("Bench")
            val press = catalogueExercise("Press")
            val push = repository.insert(
                WorkoutTemplate(name = "Push Day"),
                listOf(bench, press).map { TemplateExerciseDraft(exerciseId = it) },
            )
            val legs = repository.insert(
                WorkoutTemplate(name = "Leg Day"),
                listOf(TemplateExerciseDraft(exerciseId = squat)),
            )

            val all = repository.getAllExercises().first()

            assertEquals(listOf(push, push, legs), all.map { it.templateId })
            assertEquals(listOf(bench, press, squat), all.map { it.exerciseId })
            assertEquals(listOf(0, 1, 0), all.map { it.position })
        }

    @Test
    fun `getAllExercises is empty when no template has been saved`() =
        runBlocking {
            assertEquals(emptyList<Long>(), repository.getAllExercises().first().map { it.id })
        }

    @Test
    fun `delete is a no-op for an id that was never stored`() =
        runBlocking {
            val squat = catalogueExercise("Squat")
            val id = repository.insert(WorkoutTemplate(name = "Push Day"), listOf(TemplateExerciseDraft(squat)))

            repository.delete(404)

            assertEquals(1, countRows("workout_template"))
            assertEquals("Push Day", repository.getById(id).first()?.name)
        }
}
