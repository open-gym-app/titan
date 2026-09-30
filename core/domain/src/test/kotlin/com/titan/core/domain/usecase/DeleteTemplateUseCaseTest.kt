package com.titan.core.domain.usecase

import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.model.TemplateExercise
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteTemplateUseCaseTest {

    private val templates = FakeTemplateRepository()
    private val deleteTemplate = DeleteTemplateUseCase(templates)

    @Test
    fun `deleting removes the template`() =
        runTest {
            val id = templates.seed("Push day")

            val result = deleteTemplate(id)

            assertTrue(result.isSuccess)
            assertNull(templates.stored(id))
        }

    @Test
    fun `deleting removes the template's exercises`() =
        runTest {
            val id = templates.seed("Push day", listOf(draft(exerciseId = 1L), draft(exerciseId = 2L)))

            deleteTemplate(id).getOrThrow()

            assertEquals(emptyList<TemplateExercise>(), templates.storedExercises(id))
        }

    @Test
    fun `deleting leaves the other templates alone`() =
        runTest {
            val id = templates.seed("Push day")
            val kept = templates.seed("Pull day")

            deleteTemplate(id).getOrThrow()

            assertEquals("Pull day", templates.stored(kept)?.name)
            assertEquals(1, templates.storedCount())
        }

    @Test
    fun `deleting a template that does not exist is refused`() =
        runTest {
            val result = deleteTemplate(404L)

            assertEquals(TemplateError.NotFound(404L), result.exceptionOrNull())
        }

    @Test
    fun `a refused delete writes nothing`() =
        runTest {
            templates.seed("Push day")

            deleteTemplate(404L)

            assertEquals(0, templates.writeCount)
            assertEquals(1, templates.storedCount())
        }

    @Test
    fun `deleting the same template twice is refused the second time`() =
        runTest {
            val id = templates.seed("Push day")

            deleteTemplate(id).getOrThrow()
            val second = deleteTemplate(id)

            assertEquals(TemplateError.NotFound(id), second.exceptionOrNull())
            assertEquals(1, templates.writeCount)
        }
}
