package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.WorkoutTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateTemplateUseCaseTest {

    private val templates = FakeTemplateRepository()
    private val createTemplate = CreateTemplateUseCase(templates)

    @Test
    fun `creating returns the stored template with its assigned id`() = runTest {
        val created = createTemplate("Push day", listOf(draft(exerciseId = 7L))).getOrThrow()

        assertEquals(WorkoutTemplate(id = 1L, name = "Push day", icon = null), created)
        assertEquals(created, templates.stored(1L))
    }

    @Test
    fun `creating carries the icon onto the template`() = runTest {
        val created = createTemplate("Pull day", listOf(draft(exerciseId = 7L)), icon = "arm")
            .getOrThrow()

        assertEquals("arm", templates.stored(created.id)?.icon)
    }

    @Test
    fun `creating stores the exercises in the order given, numbered from zero`() = runTest {
        val created = createTemplate(
            "Leg day",
            listOf(draft(exerciseId = 3L), draft(exerciseId = 1L), draft(exerciseId = 2L)),
        ).getOrThrow()

        val stored = templates.storedExercises(created.id)
        assertEquals(listOf(3L, 1L, 2L), stored.map { it.exerciseId })
        assertEquals(listOf(0, 1, 2), stored.map { it.position })
    }

    @Test
    fun `creating keeps the per-exercise defaults`() = runTest {
        val created = createTemplate(
            "Leg day",
            listOf(
                draft(
                    exerciseId = 3L,
                    defaultSets = 4,
                    defaultReps = 8,
                    defaultWeight = 60_000L,
                    restDuration = 120L,
                    notes = "belt on",
                ),
            ),
        ).getOrThrow()

        val stored = templates.storedExercises(created.id).single()
        assertEquals(4, stored.defaultSets)
        assertEquals(8, stored.defaultReps)
        assertEquals(60_000L, stored.defaultWeight)
        assertEquals(120L, stored.restDuration)
        assertEquals("belt on", stored.notes)
    }

    @Test
    fun `creating trims the name before storing it`() = runTest {
        val created = createTemplate("  Push day  ", listOf(draft(exerciseId = 1L))).getOrThrow()

        assertEquals("Push day", created.name)
    }

    @Test
    fun `an empty name is refused`() = runTest {
        val result = createTemplate("", listOf(draft(exerciseId = 1L)))

        assertEquals(TemplateError.EmptyName, result.exceptionOrNull())
    }

    @Test
    fun `a whitespace-only name is refused as empty`() = runTest {
        val result = createTemplate("   ", listOf(draft(exerciseId = 1L)))

        assertEquals(TemplateError.EmptyName, result.exceptionOrNull())
    }

    @Test
    fun `a template with no exercises is refused`() = runTest {
        val result = createTemplate("Push day", emptyList())

        assertEquals(TemplateError.NoExercises, result.exceptionOrNull())
    }

    @Test
    fun `creating stores the superset grouping the drafts carry`() = runTest {
        val created = createTemplate(
            "Arms",
            listOf(
                draft(exerciseId = 1L),
                draft(exerciseId = 2L, supersetGroup = 0),
                draft(exerciseId = 3L, supersetGroup = 0),
            ),
        ).getOrThrow()

        assertEquals(listOf(null, 0, 0), templates.storedExercises(created.id).map { it.supersetGroup })
    }

    @Test
    fun `a group left with one member is stored as no group at all`() = runTest {
        // What an edit screen hands back after deleting one of a pair. Refusing it
        // would make the user repair a state their own removal created; a superset of
        // one is simply not a superset. See Supersets.normalize.
        val created = createTemplate(
            "Arms",
            listOf(draft(exerciseId = 1L), draft(exerciseId = 2L, supersetGroup = 0)),
        ).getOrThrow()

        assertEquals(listOf(null, null), templates.storedExercises(created.id).map { it.supersetGroup })
    }

    @Test
    fun `surviving groups are renumbered before they are stored`() = runTest {
        val created = createTemplate(
            "Arms",
            listOf(
                draft(exerciseId = 1L, supersetGroup = 5),
                draft(exerciseId = 2L, supersetGroup = 5),
                draft(exerciseId = 3L, supersetGroup = 2),
                draft(exerciseId = 4L, supersetGroup = 2),
            ),
        ).getOrThrow()

        assertEquals(listOf(0, 0, 1, 1), templates.storedExercises(created.id).map { it.supersetGroup })
    }

    @Test
    fun `a superset split by another exercise is refused`() = runTest {
        val result = createTemplate(
            "Arms",
            listOf(
                draft(exerciseId = 1L, supersetGroup = 0),
                draft(exerciseId = 2L),
                draft(exerciseId = 3L, supersetGroup = 0),
            ),
        )

        val error = result.exceptionOrNull()
        assertTrue(error is TemplateError.InvalidSuperset)
        assertTrue((error as TemplateError.InvalidSuperset).reason.contains("split"))
        assertEquals(0, templates.writeCount)
    }

    @Test
    fun `a duplicate name is refused`() = runTest {
        templates.seed("Push day")

        val result = createTemplate("Push day", listOf(draft(exerciseId = 1L)))

        assertEquals(TemplateError.DuplicateName("Push day"), result.exceptionOrNull())
    }

    @Test
    fun `a duplicate name differing only in case is refused`() = runTest {
        templates.seed("Push day")

        val result = createTemplate("PUSH DAY", listOf(draft(exerciseId = 1L)))

        assertEquals(TemplateError.DuplicateName("PUSH DAY"), result.exceptionOrNull())
    }

    @Test
    fun `a name colliding only once trimmed is refused`() = runTest {
        templates.seed("Push day")

        val result = createTemplate("  Push day  ", listOf(draft(exerciseId = 1L)))

        assertTrue(result.exceptionOrNull() is TemplateError.DuplicateName)
    }

    @Test
    fun `a refused create writes nothing`() = runTest {
        templates.seed("Push day")

        createTemplate("", listOf(draft(exerciseId = 1L)))
        createTemplate("Legs", emptyList())
        createTemplate("push day", listOf(draft(exerciseId = 1L)))

        assertEquals(0, templates.writeCount)
        assertEquals(1, templates.storedCount())
    }

    @Test
    fun `a distinct name is accepted alongside an existing template`() = runTest {
        templates.seed("Push day")

        createTemplate("Pull day", listOf(draft(exerciseId = 1L))).getOrThrow()

        assertEquals(2, templates.storedCount())
    }

    @Test
    fun `unwrapping a refusal throws IllegalArgumentException`() = runTest {
        val result = createTemplate("", listOf(draft(exerciseId = 1L)))

        assertThrows(IllegalArgumentException::class.java) { result.getOrThrow() }
    }
}
