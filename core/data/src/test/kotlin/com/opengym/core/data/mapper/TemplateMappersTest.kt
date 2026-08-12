package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.WorkoutTemplateEntity
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMappersTest {

    private val templateMapper = TemplateMapper()
    private val exerciseMapper = TemplateExerciseMapper()

    @Test
    fun `template round trips every field`() {
        val template = WorkoutTemplate(
            id = 3,
            name = "Push Day",
            category = TemplateCategory.HYPERTROPHY,
            icon = "dumbbell",
        )

        assertEquals(template, templateMapper.toDomain(templateMapper.toEntity(template)))
    }

    @Test
    fun `every category round trips`() {
        TemplateCategory.entries.forEach { category ->
            val template = WorkoutTemplate(id = 3, name = "Push Day", category = category)

            assertEquals(category, templateMapper.toDomain(templateMapper.toEntity(template)).category)
        }
    }

    @Test
    fun `a template with no category stores no category`() {
        val entity = templateMapper.toEntity(WorkoutTemplate(id = 3, name = "Push Day"))

        assertNull(entity.category)
        assertNull(templateMapper.toDomain(entity).category)
    }

    @Test
    fun `an unknown category reads as uncategorised rather than making the row unreadable`() {
        val row = WorkoutTemplateEntity(id = 3, name = "Push Day", category = "PILATES")

        val template = templateMapper.toDomain(row)

        assertNull(template.category)
        assertEquals("Push Day", template.name)
    }

    @Test
    fun `a stored exercise row maps to the domain with every field`() {
        val row = TemplateExerciseEntity(
            id = 8,
            templateId = 3,
            exerciseId = 14,
            position = 1,
            supersetGroup = 0,
            defaultSets = 4,
            defaultReps = 8,
            defaultWeight = 60_000_000,
            restDuration = 120,
            notes = "pause at the bottom",
        )

        val expected = TemplateExercise(
            id = 8,
            templateId = 3,
            exerciseId = 14,
            position = 1,
            supersetGroup = 0,
            defaultSets = 4,
            defaultReps = 8,
            defaultWeight = 60_000_000,
            restDuration = 120,
            notes = "pause at the bottom",
        )
        assertEquals(expected, exerciseMapper.toDomain(row))
    }

    @Test
    fun `a draft carries its defaults onto the row`() {
        val draft = TemplateExerciseDraft(
            exerciseId = 14,
            supersetGroup = 0,
            defaultSets = 4,
            defaultReps = 8,
            defaultWeight = 60_000_000,
            restDuration = 120,
            notes = "pause at the bottom",
        )

        val row = exerciseMapper.toEntities(templateId = 3, drafts = listOf(draft)).single()

        assertEquals(draft.exerciseId, row.exerciseId)
        assertEquals(draft.supersetGroup, row.supersetGroup)
        assertEquals(draft.defaultSets, row.defaultSets)
        assertEquals(draft.defaultReps, row.defaultReps)
        assertEquals(draft.defaultWeight, row.defaultWeight)
        assertEquals(draft.restDuration, row.restDuration)
        assertEquals(draft.notes, row.notes)
    }

    @Test
    fun `drafts are numbered from zero in the order given`() {
        val entities = exerciseMapper.toEntities(
            templateId = 3,
            drafts = listOf(
                TemplateExerciseDraft(exerciseId = 30),
                TemplateExerciseDraft(exerciseId = 10),
                TemplateExerciseDraft(exerciseId = 20),
            ),
        )

        assertEquals(listOf(0, 1, 2), entities.map { it.position })
        assertEquals(listOf(30L, 10L, 20L), entities.map { it.exerciseId })
    }

    @Test
    fun `drafts are stamped with the template they belong to`() {
        val entities = exerciseMapper.toEntities(
            templateId = 42,
            drafts = listOf(TemplateExerciseDraft(exerciseId = 1)),
        )

        assertEquals(42L, entities.single().templateId)
    }

    @Test
    fun `an empty draft list maps to no rows`() {
        assertTrue(exerciseMapper.toEntities(templateId = 3, drafts = emptyList()).isEmpty())
    }
}
