package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.TemplateError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplyTemplateUseCaseTest {

    private val templates = FakeTemplateRepository()
    private val applyTemplate = ApplyTemplateUseCase(templates)

    @Test
    fun `applying returns the exercises in template order`() = runTest {
        val id = templates.seed(
            "Push day",
            listOf(draft(exerciseId = 3L), draft(exerciseId = 1L), draft(exerciseId = 2L)),
        )

        val applied = applyTemplate(id).getOrThrow()

        assertEquals(listOf(3L, 1L, 2L), applied.exercises.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2), applied.exercises.map { it.position })
    }

    @Test
    fun `applying re-sorts a store that hands its exercises back out of order`() = runTest {
        // The use case sorts by position rather than trusting the repository's contract,
        // and this is the only case in which that sort does anything: with a store that
        // honours the ordering it is a no-op, so deleting it would pass every other test
        // here while leaving a user's workout free to come back reordered.
        val id = templates.seed(
            "Push day",
            listOf(draft(exerciseId = 3L), draft(exerciseId = 1L), draft(exerciseId = 2L)),
        )
        templates.honoursPositionOrder = false

        val applied = applyTemplate(id).getOrThrow()

        assertEquals(listOf(3L, 1L, 2L), applied.exercises.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2), applied.exercises.map { it.position })
    }

    @Test
    fun `applying carries the plan a session should be pre-filled with`() = runTest {
        val id = templates.seed(
            "Leg day",
            listOf(
                draft(
                    exerciseId = 3L,
                    sets = plannedSets(count = 5, reps = 5, weight = 100_000L),
                    restDuration = 180L,
                    notes = "warm up first",
                ),
            ),
        )

        val applied = applyTemplate(id).getOrThrow().exercises.single()

        assertEquals(3L, applied.exerciseId)
        assertEquals(5, applied.sets.size)
        assertEquals(listOf(5, 5, 5, 5, 5), applied.sets.map { it.reps })
        assertEquals(listOf(100_000L), applied.sets.map { it.weight }.distinct())
        assertEquals(180L, applied.restDuration)
        assertEquals("warm up first", applied.notes)
    }

    @Test
    fun `applying carries each set as its own, not as n copies of one`() = runTest {
        val id = templates.seed(
            "Leg day",
            listOf(
                draft(
                    exerciseId = 3L,
                    sets = listOf(
                        PlannedSet(setType = SetType.WARMUP, reps = 12, weight = 40_000L),
                        PlannedSet(reps = 8, weight = 100_000L),
                        PlannedSet(setType = SetType.DROP_SET, reps = 10, weight = 60_000L),
                    ),
                ),
            ),
        )

        val applied = applyTemplate(id).getOrThrow().exercises.single()

        assertEquals(
            listOf(SetType.WARMUP, SetType.NORMAL, SetType.DROP_SET),
            applied.sets.map { it.setType },
        )
        assertEquals(listOf(40_000L, 100_000L, 60_000L), applied.sets.map { it.weight })
        assertEquals(listOf(12, 8, 10), applied.sets.map { it.reps })
    }

    @Test
    fun `applying carries the name the session is started under`() = runTest {
        val id = templates.seed("Push day", listOf(draft(exerciseId = 1L)))

        val applied = applyTemplate(id).getOrThrow()

        assertEquals("Push day", applied.name)
        assertEquals(id, applied.templateId)
    }

    @Test
    fun `applying writes nothing`() = runTest {
        val id = templates.seed("Push day", listOf(draft(exerciseId = 1L)))

        applyTemplate(id).getOrThrow()

        assertEquals(0, templates.writeCount)
    }

    @Test
    fun `applying leaves the template in place`() = runTest {
        val id = templates.seed("Push day", listOf(draft(exerciseId = 1L)))
        val before = templates.stored(id)
        val exercisesBefore = templates.storedExercises(id)

        applyTemplate(id).getOrThrow()

        assertEquals(before, templates.stored(id))
        assertEquals(exercisesBefore, templates.storedExercises(id))
    }

    @Test
    fun `applying the same template twice returns the same plan`() = runTest {
        val id = templates.seed("Push day", listOf(draft(exerciseId = 1L), draft(exerciseId = 2L)))

        assertEquals(applyTemplate(id).getOrThrow(), applyTemplate(id).getOrThrow())
    }

    @Test
    fun `applying a template that does not exist is refused`() = runTest {
        val result = applyTemplate(404L)

        assertEquals(TemplateError.NotFound(404L), result.exceptionOrNull())
    }

    @Test
    fun `applying a template whose exercises were removed underneath is refused`() = runTest {
        val id = templates.seedEmpty("Push day")

        val result = applyTemplate(id)

        assertEquals(TemplateError.NoExercises, result.exceptionOrNull())
    }
}
