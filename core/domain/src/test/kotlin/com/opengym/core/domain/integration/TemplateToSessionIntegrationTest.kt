package com.opengym.core.domain.integration

import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.TemplateError
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.usecase.ApplyTemplateUseCase
import com.opengym.core.domain.usecase.CreateTemplateUseCase
import com.opengym.core.domain.usecase.FakeSessionRepository
import com.opengym.core.domain.usecase.FakeSessionStateRepository
import com.opengym.core.domain.usecase.FakeTemplateRepository
import com.opengym.core.domain.usecase.MutableClock
import com.opengym.core.domain.usecase.SessionLifecycleLock
import com.opengym.core.domain.usecase.StartSessionUseCase
import com.opengym.core.domain.usecase.UpdateTemplateUseCase
import com.opengym.core.domain.usecase.draft
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.domain.validation.ValidationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The path from a saved template to a running session, through both use cases.
 *
 * [ApplyTemplateUseCase] deliberately starts nothing — it is a read, and the session
 * write is the caller's separate decision — which means the wiring between the two is
 * code no single-use-case test owns. That wiring is what breaks first if either side
 * changes its mind about ordering, and it is what this pins: the plan comes out in
 * template order, the session records which template it came from and takes its name,
 * the exercises land in the plan's order, and the sets pre-filled from the template's
 * defaults are sets [SetValidator] accepts.
 */
class TemplateToSessionIntegrationTest {

    private val clock = MutableClock(now = 1_700_000_000_000L)
    private val templates = FakeTemplateRepository()
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val createTemplate = CreateTemplateUseCase(templates)
    private val updateTemplate = UpdateTemplateUseCase(templates)
    private val applyTemplate = ApplyTemplateUseCase(templates)
    private val startSession = StartSessionUseCase(sessions, state, clock, SessionLifecycleLock())

    /** A push day: five heavy bench sets, then a plank held for a minute. */
    private suspend fun pushDay(): Long = createTemplate(
        name = "Push day",
        exercises = listOf(
            draft(
                exerciseId = Catalogue.benchPress.id,
                defaultSets = 5,
                defaultReps = 5,
                defaultWeight = kg(80),
                restDuration = 180L,
                notes = "warm up first",
            ),
            draft(
                exerciseId = Catalogue.plank.id,
                defaultSets = 3,
                restDuration = 60L,
            ),
        ),
    ).getOrThrow().id

    /**
     * Starts a session from [templateId] and lays its plan out as session exercises, the
     * way a "start from template" screen would: apply, start, then write one
     * session-exercise per plan entry in the order the plan came back.
     */
    private suspend fun startFrom(templateId: Long): Pair<SessionState.Active, List<TemplateExercise>> {
        val plan = applyTemplate(templateId).getOrThrow()
        val started = startSession(templateId = templateId, name = plan.name).getOrThrow()
        plan.exercises.forEach { sessions.addExercise(started.sessionId, it.exerciseId) }
        return started to plan.exercises
    }

    @Test
    fun `a session started from a template records which template it came from`() = runTest {
        val templateId = pushDay()

        val (started, _) = startFrom(templateId)

        val session = sessions.stored(started.sessionId)
        assertEquals(templateId, session?.templateId)
        assertEquals(true, session?.isInProgress)
        assertEquals(started, state.current)
    }

    @Test
    fun `a session started from a template is named after it`() = runTest {
        val templateId = pushDay()

        val (started, _) = startFrom(templateId)

        assertEquals("Push day", sessions.stored(started.sessionId)?.name)
    }

    @Test
    fun `renaming the template afterwards leaves the session's name alone`() = runTest {
        // The name is copied at apply time, not resolved through template_id on read.
        // A user tidying up their templates in March must not retitle the workouts they
        // logged in January — and the same copy is what lets them rename this session
        // without touching the template it came from.
        val templateId = pushDay()
        val (started, _) = startFrom(templateId)

        updateTemplate(
            templateId,
            name = "Push day (v2)",
            exercises = listOf(draft(exerciseId = Catalogue.benchPress.id)),
        ).getOrThrow()

        assertEquals("Push day", sessions.stored(started.sessionId)?.name)
    }

    @Test
    fun `a session started without a template is left unnamed`() = runTest {
        // Quick start: no template, so nothing to take a name from. Null rather than a
        // fabricated title — the UI has a date-derived fallback for exactly this, and a
        // placeholder written into the row would be indistinguishable from one the user
        // chose.
        val started = startSession().getOrThrow()

        assertNull(sessions.stored(started.sessionId)?.name)
    }

    @Test
    fun `the session's exercises land in the template's order`() = runTest {
        val templateId = pushDay()

        val (started, plan) = startFrom(templateId)

        val exercises = sessions.getExercises(started.sessionId).first()
        assertEquals(plan.map(TemplateExercise::exerciseId), exercises.map { it.exerciseId })
        assertEquals(plan.map(TemplateExercise::position), exercises.map { it.position })
        assertEquals(listOf(Catalogue.benchPress.id, Catalogue.plank.id), exercises.map { it.exerciseId })
    }

    @Test
    fun `the sets pre-filled from the template's defaults are sets the validator accepts`() = runTest {
        val catalogue = Catalogue.repository()
        val templateId = pushDay()
        val (started, plan) = startFrom(templateId)
        val exercises = sessions.getExercises(started.sessionId).first()

        val prefilled = plan.zip(exercises).flatMap { (planned, exercise) ->
            val type = catalogue.exercise(planned.exerciseId).type
            List(planned.defaultSets ?: 0) {
                sessions.addSet(
                    exercise.id,
                    // Planned, not performed: the user has not done these yet, so they
                    // carry the template's numbers and no completion.
                    WorkoutSet(
                        sessionExerciseId = exercise.id,
                        setNumber = 0,
                        reps = planned.defaultReps,
                        weight = planned.defaultWeight,
                        restAfter = planned.restDuration,
                        isCompleted = false,
                        completedAt = 0L,
                        createdAt = clock.now,
                        updatedAt = clock.now,
                    ),
                ) to type
            }
        }

        assertEquals(8, prefilled.size)
        prefilled.forEach { (set, type) ->
            assertEquals(
                "a set pre-filled from the template should validate: $set",
                ValidationResult.Valid,
                SetValidator.validate(set, type),
            )
        }

        // What actually carries the three planks past the validator is `isCompleted`,
        // not the template's numbers: a plank template names no duration, and a
        // duration is precisely what an ISOMETRIC set has to record once performed.
        // Asserting the same rows are refused the moment they are marked done is what
        // stops the check above from being satisfied by a validator that had simply
        // stopped looking at the exercise type at all.
        val performedPlanks = prefilled
            .filter { (_, type) -> type == ExerciseType.ISOMETRIC }
            .map { (set, type) -> set.copy(isCompleted = true, completedAt = clock.now) to type }

        assertEquals(3, performedPlanks.size)
        performedPlanks.forEach { (set, type) ->
            assertTrue(
                "a plank marked done without a duration is not a complete record: $set",
                SetValidator.validate(set, type) is ValidationResult.Invalid,
            )
        }
    }

    @Test
    fun `a planned set carries the template's numbers and is numbered by the store`() = runTest {
        val templateId = pushDay()
        val (started, plan) = startFrom(templateId)
        val bench = sessions.getExercises(started.sessionId).first().first()
        val planned = plan.first()

        repeat(planned.defaultSets ?: 0) {
            sessions.addSet(bench.id, reps = planned.defaultReps, weight = planned.defaultWeight, isCompleted = false)
        }

        val sets = sessions.getSets(bench.id).first()
        assertEquals(5, sets.size)
        assertEquals(listOf(1, 2, 3, 4, 5), sets.map { it.setNumber })
        assertEquals(List(5) { 5 }, sets.map { it.reps })
        assertEquals(List(5) { kg(80) }, sets.map { it.weight })
    }

    @Test
    fun `the session's exercises keep template order even when the store loses it`() = runTest {
        // The composition's weakest link: the session's exercise order comes from the
        // order the plan arrives in, so a store that stopped ordering by position would
        // reorder the user's workout — silently, since every id is still present.
        // ApplyTemplateUseCase's sort is what stops that, and this is where it earns its
        // keep on the way into a session.
        val templateId = pushDay()
        templates.honoursPositionOrder = false

        val (started, exercises) = startFrom(templateId)

        assertEquals(listOf(Catalogue.benchPress.id, Catalogue.plank.id), exercises.map(TemplateExercise::exerciseId))
        assertEquals(
            listOf(Catalogue.benchPress.id, Catalogue.plank.id),
            sessions.getExercises(started.sessionId).first().map { it.exerciseId },
        )
    }

    @Test
    fun `applying a template that is gone starts no session`() = runTest {
        // The two use cases being separate is what makes this recoverable: a template
        // deleted on another screen refuses the read, and nothing has been written yet.
        val result = applyTemplate(templateId = 404L)

        assertEquals(TemplateError.NotFound(404L), result.exceptionOrNull())
        assertEquals(SessionState.Idle, state.current)
        assertNull(sessions.getActive().first())
    }

    @Test
    fun `applying writes nothing, so the session is what starts the workout`() = runTest {
        val templateId = pushDay()
        val writesAfterCreate = templates.writeCount

        applyTemplate(templateId).getOrThrow()

        assertEquals(writesAfterCreate, templates.writeCount)
        assertEquals(SessionState.Idle, state.current)
        assertNull(sessions.getActive().first())
    }

    @Test
    fun `a second session cannot be started from the same template while the first runs`() = runTest {
        val templateId = pushDay()
        startFrom(templateId)

        val plan = applyTemplate(templateId)
        val second = startSession(templateId = templateId)

        // Applying still succeeds — it is a read, and reading a template is always legal.
        // It is the start that is refused, which is where the one-session invariant lives.
        assertTrue(plan.isSuccess)
        assertTrue(second.isFailure)
        assertEquals(1, sessions.getAll().first().size)
    }
}
