package com.opengym.core.domain.usecase

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetTemplatesUseCaseTest {

    private val templates = FakeTemplateRepository()
    private val getTemplates = GetTemplatesUseCase(templates)
    private val createTemplate = CreateTemplateUseCase(templates)
    private val deleteTemplate = DeleteTemplateUseCase(templates)

    @Test
    fun `an empty store emits an empty list rather than nothing`() = runTest {
        assertEquals(emptyList<Long>(), getTemplates().first())
    }

    @Test
    fun `templates are emitted ordered by name`() = runTest {
        templates.seed("Push day")
        templates.seed("Arms")
        templates.seed("Legs")

        assertEquals(listOf("Arms", "Legs", "Push day"), getTemplates().first().map { it.name })
    }

    @Test
    fun `the list re-emits when a template is created`() = runTest {
        getTemplates().test {
            assertEquals(emptyList<Long>(), awaitItem())

            createTemplate("Push day", listOf(draft(exerciseId = 1L))).getOrThrow()

            assertEquals(listOf("Push day"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the list re-emits when a template is deleted`() = runTest {
        val id = templates.seed("Push day")

        getTemplates().test {
            assertEquals(listOf("Push day"), awaitItem().map { it.name })

            deleteTemplate(id).getOrThrow()

            assertEquals(emptyList<Long>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
