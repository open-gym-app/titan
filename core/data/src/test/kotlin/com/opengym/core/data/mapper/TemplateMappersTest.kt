package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.TemplateSetEntity
import com.opengym.core.data.db.entity.WorkoutTemplateEntity
import com.opengym.core.data.db.projection.TemplateExerciseWithSets
import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SetType
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
    fun `a stored exercise row and its sets map to the domain with every field`() {
        val row = TemplateExerciseWithSets(
            exercise = TemplateExerciseEntity(
                id = 8,
                templateId = 3,
                exerciseId = 14,
                position = 1,
                supersetGroup = 0,
                restDuration = 120,
                notes = "pause at the bottom",
            ),
            sets = listOf(
                TemplateSetEntity(
                    id = 1,
                    templateExerciseId = 8,
                    setNumber = 1,
                    setType = "WARMUP",
                    reps = 12,
                    weight = 40_000_000,
                ),
                TemplateSetEntity(id = 2, templateExerciseId = 8, setNumber = 2, reps = 8, weight = 60_000_000),
            ),
        )

        val expected = TemplateExercise(
            id = 8,
            templateId = 3,
            exerciseId = 14,
            position = 1,
            supersetGroup = 0,
            sets = listOf(
                PlannedSet(setType = SetType.WARMUP, reps = 12, weight = 40_000_000),
                PlannedSet(reps = 8, weight = 60_000_000),
            ),
            restDuration = 120,
            notes = "pause at the bottom",
        )
        assertEquals(expected, exerciseMapper.toDomain(row))
    }

    @Test
    fun `the sets are put back in set-number order, whatever order the relation returns`() {
        // `@Relation` promises the rows and not their order — see `TemplateExerciseWithSets`.
        // A plan read back with its warm-up in the middle is the wrong plan.
        val row = TemplateExerciseWithSets(
            exercise = TemplateExerciseEntity(id = 8, templateId = 3, exerciseId = 14, position = 0),
            sets = listOf(
                TemplateSetEntity(id = 3, templateExerciseId = 8, setNumber = 3, reps = 6),
                TemplateSetEntity(id = 1, templateExerciseId = 8, setNumber = 1, reps = 12),
                TemplateSetEntity(id = 2, templateExerciseId = 8, setNumber = 2, reps = 8),
            ),
        )

        assertEquals(listOf(12, 8, 6), exerciseMapper.toDomain(row).sets.map(PlannedSet::reps))
    }

    @Test
    fun `an unknown set type reads as a standard set rather than making the plan unreadable`() {
        val row = TemplateExerciseWithSets(
            exercise = TemplateExerciseEntity(id = 8, templateId = 3, exerciseId = 14, position = 0),
            sets = listOf(TemplateSetEntity(templateExerciseId = 8, setNumber = 1, setType = "CLUSTER")),
        )

        assertEquals(SetType.NORMAL, exerciseMapper.toDomain(row).sets.single().setType)
    }

    @Test
    fun `a draft carries its plan onto the rows`() {
        val draft = TemplateExerciseDraft(
            exerciseId = 14,
            sets = listOf(
                PlannedSet(setType = SetType.WARMUP, reps = 12, weight = 40_000_000),
                PlannedSet(reps = 8, weight = 60_000_000),
                PlannedSet(setType = SetType.DROP_SET, reps = 10, weight = 30_000_000),
            ),
            restDuration = 120,
            notes = "pause at the bottom",
            supersetGroup = 0,
        )

        val row = exerciseMapper.toEntities(templateId = 3, drafts = listOf(draft)).single()

        assertEquals(draft.exerciseId, row.exercise.exerciseId)
        assertEquals(draft.supersetGroup, row.exercise.supersetGroup)
        assertEquals(draft.restDuration, row.exercise.restDuration)
        assertEquals(draft.notes, row.exercise.notes)
        assertEquals(listOf("WARMUP", "NORMAL", "DROP_SET"), row.sets.map { it.setType })
        assertEquals(listOf(12, 8, 10), row.sets.map { it.reps })
        assertEquals(listOf(40_000_000L, 60_000_000L, 30_000_000L), row.sets.map { it.weight })
    }

    @Test
    fun `planned sets are numbered from one in the order given`() {
        val draft = TemplateExerciseDraft(exerciseId = 14, sets = List(4) { PlannedSet(reps = it) })

        val row = exerciseMapper.toEntities(templateId = 3, drafts = listOf(draft)).single()

        assertEquals(listOf(1, 2, 3, 4), row.sets.map { it.setNumber })
        assertEquals(listOf(0, 1, 2, 3), row.sets.map { it.reps })
    }

    @Test
    fun `a set's exercise id is left for the DAO to stamp, since it does not exist yet`() {
        val draft = TemplateExerciseDraft(exerciseId = 14, sets = listOf(PlannedSet()))

        val row = exerciseMapper.toEntities(templateId = 3, drafts = listOf(draft)).single()

        assertEquals(0L, row.sets.single().templateExerciseId)
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

        assertEquals(listOf(0, 1, 2), entities.map { it.exercise.position })
        assertEquals(listOf(30L, 10L, 20L), entities.map { it.exercise.exerciseId })
    }

    @Test
    fun `drafts are stamped with the template they belong to`() {
        val entities = exerciseMapper.toEntities(
            templateId = 42,
            drafts = listOf(TemplateExerciseDraft(exerciseId = 1)),
        )

        assertEquals(42L, entities.single().exercise.templateId)
    }

    @Test
    fun `an empty draft list maps to no rows`() {
        assertTrue(exerciseMapper.toEntities(templateId = 3, drafts = emptyList()).isEmpty())
    }
}
