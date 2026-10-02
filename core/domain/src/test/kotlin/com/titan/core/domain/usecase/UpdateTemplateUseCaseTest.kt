package com.titan.core.domain.usecase

import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.template.TemplateDuration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTemplateUseCaseTest {

    private val templates = FakeTemplateRepository()
    private val updateTemplate = UpdateTemplateUseCase(templates)

    @Test
    fun `updating rewrites the name`() =
        runTest {
            val id = templates.seed("Push day")

            val updated = updateTemplate(id, "Upper body", listOf(draft(exerciseId = 1L))).getOrThrow()

            assertEquals(WorkoutTemplate(id = id, name = "Upper body", icon = null), updated)
            assertEquals("Upper body", templates.stored(id)?.name)
        }

    @Test
    fun `updating rewrites the category`() =
        runTest {
            val id = templates.seed("Push day", category = TemplateCategory.STRENGTH)

            updateTemplate(
                id,
                "Push day",
                listOf(draft(exerciseId = 1L)),
                category = TemplateCategory.HYPERTROPHY,
            ).getOrThrow()

            assertEquals(TemplateCategory.HYPERTROPHY, templates.stored(id)?.category)
        }

    @Test
    fun `updating without a category clears the one that was there`() =
        runTest {
            // A rewrite, not a patch: an edit screen that dropped the category is how a
            // user removes one, so a merge with the stored row would make it unremovable.
            val id = templates.seed("Push day", category = TemplateCategory.STRENGTH)

            updateTemplate(id, "Push day", listOf(draft(exerciseId = 1L))).getOrThrow()

            assertNull(templates.stored(id)?.category)
        }

    @Test
    fun `editing the exercises changes the estimated duration without a second write`() =
        runTest {
            val id = templates.seed("Push day", listOf(draft(exerciseId = 1L, sets = plannedSets(3))))
            val before = estimatedDuration(id)
            val writesBefore = templates.writeCount

            updateTemplate(
                id,
                "Push day",
                listOf(draft(exerciseId = 1L, sets = plannedSets(3)), draft(exerciseId = 2L, sets = plannedSets(4))),
            ).getOrThrow()

            // One write, the one that stored the new exercise list. Nothing restates the
            // duration afterwards because no row holds it.
            assertEquals(writesBefore + 1, templates.writeCount)
            assertTrue(estimatedDuration(id) > before)
        }

    @Test
    fun `updating replaces the whole exercise list`() =
        runTest {
            val id = templates.seed("Push day", listOf(draft(exerciseId = 1L), draft(exerciseId = 2L)))

            updateTemplate(id, "Push day", listOf(draft(exerciseId = 9L))).getOrThrow()

            val stored = templates.storedExercises(id)
            assertEquals(listOf(9L), stored.map { it.exerciseId })
            assertEquals(listOf(0), stored.map { it.position })
        }

    @Test
    fun `updating renumbers positions from the new order`() =
        runTest {
            val id = templates.seed("Push day")

            updateTemplate(
                id,
                "Push day",
                listOf(draft(exerciseId = 5L), draft(exerciseId = 4L), draft(exerciseId = 6L)),
            ).getOrThrow()

            val stored = templates.storedExercises(id)
            assertEquals(listOf(5L, 4L, 6L), stored.map { it.exerciseId })
            assertEquals(listOf(0, 1, 2), stored.map { it.position })
        }

    @Test
    fun `keeping the template's own name is not a duplicate`() =
        runTest {
            val id = templates.seed("Push day")

            val updated = updateTemplate(id, "Push day", listOf(draft(exerciseId = 1L))).getOrThrow()

            assertEquals("Push day", updated.name)
        }

    @Test
    fun `re-casing the template's own name is not a duplicate`() =
        runTest {
            val id = templates.seed("Push day")

            val updated = updateTemplate(id, "PUSH DAY", listOf(draft(exerciseId = 1L))).getOrThrow()

            assertEquals("PUSH DAY", updated.name)
        }

    @Test
    fun `taking another template's name is refused`() =
        runTest {
            val id = templates.seed("Push day")
            templates.seed("Pull day")

            val result = updateTemplate(id, "Pull day", listOf(draft(exerciseId = 1L)))

            assertEquals(TemplateError.DuplicateName("Pull day"), result.exceptionOrNull())
        }

    @Test
    fun `taking another template's name in a different case is refused`() =
        runTest {
            val id = templates.seed("Push day")
            templates.seed("Pull day")

            val result = updateTemplate(id, "pull DAY", listOf(draft(exerciseId = 1L)))

            assertTrue(result.exceptionOrNull() is TemplateError.DuplicateName)
        }

    @Test
    fun `an empty name is refused`() =
        runTest {
            val id = templates.seed("Push day")

            val result = updateTemplate(id, "  ", listOf(draft(exerciseId = 1L)))

            assertEquals(TemplateError.EmptyName, result.exceptionOrNull())
        }

    @Test
    fun `emptying the exercise list is refused`() =
        runTest {
            val id = templates.seed("Push day")

            val result = updateTemplate(id, "Push day", emptyList())

            assertEquals(TemplateError.NoExercises, result.exceptionOrNull())
        }

    @Test
    fun `editing a template into a split superset is refused`() =
        runTest {
            // The rule Create enforces, enforced again here: a template must not be
            // editable into a shape it could not have been created in.
            val id = templates.seed("Push day")

            val result = updateTemplate(
                id,
                "Push day",
                listOf(
                    draft(exerciseId = 1L, supersetGroup = 0),
                    draft(exerciseId = 2L),
                    draft(exerciseId = 3L, supersetGroup = 0),
                ),
            )

            assertTrue(result.exceptionOrNull() is TemplateError.InvalidSuperset)
            assertEquals(0, templates.writeCount)
        }

    @Test
    fun `an edit that drops one of a pair stores the survivor as standalone`() =
        runTest {
            val id = templates.seed(
                "Push day",
                listOf(draft(exerciseId = 1L, supersetGroup = 0), draft(exerciseId = 2L, supersetGroup = 0)),
            )

            updateTemplate(id, "Push day", listOf(draft(exerciseId = 1L, supersetGroup = 0))).getOrThrow()

            assertEquals(listOf(null), templates.storedExercises(id).map { it.supersetGroup })
        }

    @Test
    fun `updating a template that does not exist is refused`() =
        runTest {
            val result = updateTemplate(404L, "Push day", listOf(draft(exerciseId = 1L)))

            assertEquals(TemplateError.NotFound(404L), result.exceptionOrNull())
        }

    @Test
    fun `a refused update leaves the stored template untouched`() =
        runTest {
            val id = templates.seed("Push day", listOf(draft(exerciseId = 1L)))

            updateTemplate(id, "", listOf(draft(exerciseId = 2L)))
            updateTemplate(id, "Renamed", emptyList())

            assertEquals(0, templates.writeCount)
            assertEquals("Push day", templates.stored(id)?.name)
            assertEquals(listOf(1L), templates.storedExercises(id).map { it.exerciseId })
        }

    /** What a screen would render for the stored template, read back from its rows. */
    private fun estimatedDuration(id: Long) =
        TemplateDuration.estimate(templates.storedExercises(id), UserPreferences.DEFAULT_REST_DURATION)
}
