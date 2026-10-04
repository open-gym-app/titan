package com.titan.core.domain.usecase

import com.titan.core.domain.model.DefaultTemplate
import com.titan.core.domain.model.DefaultTemplateName
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.repository.DefaultTemplateCatalogue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreDefaultTemplatesUseCaseTest {

    private val templates = FakeTemplateRepository()
    private val catalogue = FakeDefaultTemplateCatalogue(
        listOf(
            default("Push", exerciseId = 1L),
            default("Legs", exerciseId = 2L, category = TemplateCategory.STRENGTH),
            default("Arms", exerciseId = 3L),
        ),
    )
    private val restore = RestoreDefaultTemplatesUseCase(catalogue, templates, CreateTemplateUseCase(templates))

    @Test
    fun `nothing missing adds nothing and reports zero`() =
        runTest {
            templates.seed("Push")
            templates.seed("Legs")
            templates.seed("Arms")

            assertEquals(emptyList<DefaultTemplateName>(), restore.observeMissing().first())
            assertEquals(0, restore().getOrThrow())
            assertEquals(0, templates.insertCount)
        }

    @Test
    fun `only the missing defaults are added, as their definitions describe them`() =
        runTest {
            templates.seed("Push")

            assertEquals(listOf("Legs", "Arms"), restore.observeMissing().first().map { it.current })
            assertEquals(2, restore().getOrThrow())

            val legs = templates.getAll().first().single { it.name == "Legs" }
            assertEquals(TemplateCategory.STRENGTH, legs.category)
            assertEquals(listOf(2L), templates.storedExercises(legs.id).map { it.exerciseId })
            assertEquals(emptyList<DefaultTemplateName>(), restore.observeMissing().first())
        }

    @Test
    fun `an edited default keeps its edits`() =
        runTest {
            val push = templates.seed("Push", drafts = listOf(draft(exerciseId = 99L)))
            templates.seed("Legs")
            templates.seed("Arms")

            restore().getOrThrow()

            assertEquals(listOf(99L), templates.storedExercises(push).map { it.exerciseId })
            assertEquals(3, templates.storedCount())
        }

    @Test
    fun `a renamed default counts as gone, and its original comes back beside it`() =
        runTest {
            templates.seed("Leg day")
            templates.seed("Push")
            templates.seed("Arms")

            assertEquals(1, restore().getOrThrow())

            assertEquals(
                listOf("Arms", "Leg day", "Legs", "Push"),
                templates.getAll().first().map { it.name },
            )
        }

    @Test
    fun `a case-only rename is still the default`() =
        runTest {
            templates.seed("legs")
            templates.seed("PUSH")
            templates.seed("aRmS")

            assertEquals(emptyList<DefaultTemplateName>(), restore.observeMissing().first())
            assertEquals(0, restore().getOrThrow())
        }

    @Test
    fun `the missing list follows the template list as it changes`() =
        runTest {
            val legs = templates.seed("Legs")
            templates.seed("Push")
            templates.seed("Arms")
            assertEquals(emptyList<DefaultTemplateName>(), restore.observeMissing().first())

            templates.delete(legs)

            assertEquals(listOf("Legs"), restore.observeMissing().first().map { it.current })
        }

    @Test
    fun `a default seeded in another language is still present`() =
        runTest {
            // Seeded on an English phone, looked for on a French one.
            templates.seed("Legs")
            templates.seed("Push")
            templates.seed("Arms")
            val restore = RestoreDefaultTemplatesUseCase(frenchCatalogue(), templates, CreateTemplateUseCase(templates))

            assertEquals(emptyList<DefaultTemplateName>(), restore.observeMissing().first())
            assertEquals(0, restore().getOrThrow())
            assertEquals(3, templates.storedCount())
        }

    @Test
    fun `a missing default comes back under its name in the current language`() =
        runTest {
            templates.seed("Push")
            templates.seed("Arms")
            val restore = RestoreDefaultTemplatesUseCase(frenchCatalogue(), templates, CreateTemplateUseCase(templates))

            assertEquals(listOf("Jambes"), restore.observeMissing().first().map { it.current })
            assertEquals(1, restore().getOrThrow())

            assertEquals(listOf("Arms", "Jambes", "Push"), templates.getAll().first().map { it.name })
        }

    @Test
    fun `a definition that fails validation surfaces as its template error`() =
        runTest {
            val broken = RestoreDefaultTemplatesUseCase(
                FakeDefaultTemplateCatalogue(
                    listOf(DefaultTemplate(DefaultTemplateName("Empty"), TemplateCategory.GENERAL, emptyList())),
                ),
                templates,
                CreateTemplateUseCase(templates),
            )

            val failure = broken().exceptionOrNull()

            assertTrue("expected NoExercises, was $failure", failure is TemplateError.NoExercises)
            assertEquals(0, templates.storedCount())
        }

    private fun default(
        name: String,
        exerciseId: Long,
        category: TemplateCategory = TemplateCategory.HYPERTROPHY,
    ) = default(DefaultTemplateName(name), exerciseId, category)

    private fun default(
        name: DefaultTemplateName,
        exerciseId: Long,
        category: TemplateCategory = TemplateCategory.HYPERTROPHY,
    ) = DefaultTemplate(
        name = name,
        category = category,
        exercises = listOf(draft(exerciseId = exerciseId, sets = plannedSets(count = 3, reps = 10))),
    )

    /** The same three defaults on a phone now in French, shipped in English and French. */
    private fun frenchCatalogue() =
        FakeDefaultTemplateCatalogue(
            listOf(
                default(DefaultTemplateName("Poussée", setOf("Push", "Poussée")), exerciseId = 1L),
                default(DefaultTemplateName("Jambes", setOf("Legs", "Jambes")), exerciseId = 2L),
                default(DefaultTemplateName("Bras", setOf("Arms", "Bras")), exerciseId = 3L),
            ),
        )

    private class FakeDefaultTemplateCatalogue(private val defaults: List<DefaultTemplate>) : DefaultTemplateCatalogue {
        override fun getNames(): List<DefaultTemplateName> = defaults.map { it.name }

        override suspend fun getAll(): List<DefaultTemplate> = defaults
    }
}
