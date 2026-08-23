package com.opengym.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanTest {

    @Test
    fun `a plan from picked exercises keeps the order they were picked in`() {
        val plan = SessionPlan.fromExercises(listOf(9L, 3L, 7L))

        assertEquals(listOf(9L, 3L, 7L), plan.exercises.map(SessionExerciseDraft::exerciseId))
    }

    @Test
    fun `a plan from picked exercises has no template, no name and no supersets`() {
        val plan = SessionPlan.fromExercises(listOf(1L, 2L))

        assertNull(plan.templateId)
        assertNull(plan.name)
        assertEquals(listOf(null, null), plan.exercises.map(SessionExerciseDraft::supersetGroup))
    }

    @Test
    fun `a plan from no picked exercises is empty rather than refused`() {
        assertEquals(emptyList<SessionExerciseDraft>(), SessionPlan.fromExercises(emptyList()).exercises)
    }

    @Test
    fun `a plan from a template takes its name and its id`() {
        val plan = templatePlan(name = "Push day · Upper").toSessionPlan()

        assertEquals("Push day · Upper", plan.name)
        assertEquals(TEMPLATE_ID, plan.templateId)
    }

    @Test
    fun `a plan from a template keeps its order and its supersets`() {
        val plan = templatePlan(
            exercises = listOf(
                templateExercise(id = 10L, exerciseId = 5L, position = 0),
                templateExercise(id = 11L, exerciseId = 6L, position = 1, supersetGroup = 0),
                templateExercise(id = 12L, exerciseId = 7L, position = 2, supersetGroup = 0),
            ),
        ).toSessionPlan()

        assertEquals(listOf(5L, 6L, 7L), plan.exercises.map(SessionExerciseDraft::exerciseId))
        assertEquals(listOf(null, 0, 0), plan.exercises.map(SessionExerciseDraft::supersetGroup))
    }

    @Test
    fun `a plan from a template links each exercise back to the template line`() {
        val plan = templatePlan(
            exercises = listOf(
                templateExercise(id = 10L, exerciseId = 5L, position = 0),
                templateExercise(id = 11L, exerciseId = 6L, position = 1),
            ),
        ).toSessionPlan()

        assertEquals(listOf(10L, 11L), plan.exercises.map(SessionExerciseDraft::templateExerciseId))
    }

    @Test
    fun `a plan from a template drops the template's note, which is not the session's`() {
        val plan = templatePlan(
            exercises = listOf(
                templateExercise(
                    id = 10L,
                    exerciseId = 5L,
                    position = 0,
                    notes = "the template's note",
                ),
            ),
        ).toSessionPlan()

        // The template's own note describes how to program the exercise, not what happened
        // in this session; `session_exercise.notes` is the user's, written while training.
        assertNull(plan.exercises.single().notes)
    }

    @Test
    fun `a plan from a template carries every set it plans, in order`() {
        // What the session is about to be laid out with. Dropping these was the whole of the
        // bug: a four-set template started a workout with one empty row in it.
        val sets = listOf(
            PlannedSet(setType = SetType.WARMUP, reps = 12, weight = 40_000_000),
            PlannedSet(reps = 8, weight = 80_000_000),
            PlannedSet(setType = SetType.DROP_SET, reps = 10, weight = 60_000_000),
        )
        val plan = templatePlan(
            exercises = listOf(templateExercise(id = 10L, exerciseId = 5L, position = 0, sets = sets)),
        ).toSessionPlan()

        assertEquals(sets, plan.exercises.single().sets)
    }

    @Test
    fun `an exercise a template plans no sets for arrives with none`() {
        val plan = templatePlan(
            exercises = listOf(templateExercise(id = 10L, exerciseId = 5L, position = 0)),
        ).toSessionPlan()

        assertTrue(plan.exercises.single().sets.isEmpty())
    }

    private fun templatePlan(
        name: String = "Push day",
        exercises: List<TemplateExercise> = listOf(templateExercise(id = 10L, exerciseId = 5L, position = 0)),
    ) = TemplatePlan(templateId = TEMPLATE_ID, name = name, exercises = exercises)

    private fun templateExercise(
        id: Long,
        exerciseId: Long,
        position: Int,
        supersetGroup: Int? = null,
        notes: String? = null,
        sets: List<PlannedSet> = emptyList(),
    ) = TemplateExercise(
        id = id,
        templateId = TEMPLATE_ID,
        exerciseId = exerciseId,
        position = position,
        supersetGroup = supersetGroup,
        sets = sets,
        notes = notes,
    )

    private companion object {
        const val TEMPLATE_ID = 42L
    }
}
