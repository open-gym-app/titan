package com.titan.core.domain.integration

import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.toSessionPlan
import com.titan.core.domain.superset.Supersets
import com.titan.core.domain.usecase.ApplyTemplateUseCase
import com.titan.core.domain.usecase.CreateTemplateUseCase
import com.titan.core.domain.usecase.FakeSessionRepository
import com.titan.core.domain.usecase.FakeSessionStateRepository
import com.titan.core.domain.usecase.FakeTemplateRepository
import com.titan.core.domain.usecase.MutableClock
import com.titan.core.domain.usecase.SessionLifecycleLock
import com.titan.core.domain.usecase.StartPlannedSessionUseCase
import com.titan.core.domain.usecase.StartSessionUseCase
import com.titan.core.domain.usecase.UpdateTemplateUseCase
import com.titan.core.domain.usecase.draft
import com.titan.core.domain.usecase.plannedSets
import com.titan.core.domain.validation.SetValidator
import com.titan.core.domain.validation.ValidationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * the exercises land in the plan's order, and every set the template planned is in the
 * workout before the lifter has typed anything — as a row [SetValidator] accepts.
 *
 * That last one is the part that used to be missing. A template held one set count per
 * exercise, the plan dropped it on the way to the session because `session_exercise` had
 * nowhere to put it, and a four-set template opened a workout with one empty row in it.
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
    private val startPlanned = StartPlannedSessionUseCase(startSession, sessions, state)

    /** A push day: five heavy bench sets, then a plank held for a minute. */
    private suspend fun pushDay(): Long =
        createTemplate(
            name = "Push day",
            exercises = listOf(
                draft(
                    exerciseId = Catalogue.benchPress.id,
                    sets = plannedSets(count = 5, reps = 5, weight = kg(80)),
                    restDuration = 180L,
                    notes = "warm up first",
                ),
                draft(
                    exerciseId = Catalogue.plank.id,
                    sets = plannedSets(3),
                    restDuration = 60L,
                ),
            ),
        ).getOrThrow().id

    /**
     * Starts a session from [templateId] the way the new-session flow does: apply the
     * template, turn the plan into a session plan, and hand that to
     * [StartPlannedSessionUseCase], which writes the row, its exercises and its sets.
     *
     * The composition under test *is* those three calls, so the test makes them rather than
     * writing the rows itself — a helper that laid the session out by hand would pass however
     * the real path behaved.
     */
    private suspend fun startFrom(templateId: Long): Pair<SessionState.Active, List<TemplateExercise>> {
        val plan = applyTemplate(templateId).getOrThrow()
        startPlanned(plan.toSessionPlan()).getOrThrow()
        return state.current as SessionState.Active to plan.exercises
    }

    @Test
    fun `a session started from a template records which template it came from`() =
        runTest {
            val templateId = pushDay()

            val (started, _) = startFrom(templateId)

            val session = sessions.stored(started.sessionId)
            assertEquals(templateId, session?.templateId)
            assertEquals(true, session?.isInProgress)
            assertEquals(started, state.current)
        }

    @Test
    fun `a session started from a template is named after it`() =
        runTest {
            val templateId = pushDay()

            val (started, _) = startFrom(templateId)

            assertEquals("Push day", sessions.stored(started.sessionId)?.name)
        }

    @Test
    fun `renaming the template afterwards leaves the session's name alone`() =
        runTest {
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
    fun `a session started without a template is left unnamed`() =
        runTest {
            // Quick start: no template, so nothing to take a name from. Null rather than a
            // fabricated title — the UI has a date-derived fallback for exactly this, and a
            // placeholder written into the row would be indistinguishable from one the user
            // chose.
            val started = startSession().getOrThrow()

            assertNull(requireNotNull(sessions.stored(started.sessionId)).name)
        }

    @Test
    fun `the session's exercises land in the template's order`() =
        runTest {
            val templateId = pushDay()

            val (started, plan) = startFrom(templateId)

            val exercises = sessions.getExercises(started.sessionId).first()
            assertEquals(plan.map(TemplateExercise::exerciseId), exercises.map { it.exerciseId })
            assertEquals(plan.map(TemplateExercise::position), exercises.map { it.position })
            assertEquals(listOf(Catalogue.benchPress.id, Catalogue.plank.id), exercises.map { it.exerciseId })
        }

    @Test
    fun `the sets the template planned are in the workout before anything is typed`() =
        runTest {
            // The bug this file exists to keep fixed: a template with two exercises and eight
            // sets between them used to open a workout with two exercises and no sets at all.
            val templateId = pushDay()

            val (started, _) = startFrom(templateId)

            val exercises = sessions.getExercises(started.sessionId).first()
            val sets = exercises.flatMap { sessions.getSets(it.id).first() }
            assertEquals(8, sets.size)
            assertEquals(listOf(5, 3), exercises.map { sessions.getSets(it.id).first().size })
        }

    @Test
    fun `a planned set is in the table, numbered, and counts towards nothing yet`() =
        runTest {
            val templateId = pushDay()
            val (started, _) = startFrom(templateId)
            val bench = sessions.getExercises(started.sessionId).first().first()

            val sets = sessions.getSets(bench.id).first()

            assertEquals(listOf(1, 2, 3, 4, 5), sets.map { it.setNumber })
            assertEquals(List(5) { 5 }, sets.map { it.reps })
            assertEquals(List(5) { kg(80) }, sets.map { it.weight })
            // Planned, not performed. `completed_at` is the placeholder PERSISTENCE.md names,
            // never a time: a set filed at epoch 1970 would land in every aggregate that reads
            // the column.
            assertTrue(sets.none(WorkoutSet::isCompleted))
            assertEquals(List(5) { 0L }, sets.map { it.completedAt })
        }

    @Test
    fun `each planned set keeps its own type and weight`() =
        runTest {
            val templateId = createTemplate(
                name = "Ramped bench",
                exercises = listOf(
                    draft(
                        exerciseId = Catalogue.benchPress.id,
                        sets = listOf(
                            PlannedSet(setType = SetType.WARMUP, reps = 12, weight = kg(40)),
                            PlannedSet(reps = 5, weight = kg(80)),
                            PlannedSet(reps = 5, weight = kg(85)),
                            PlannedSet(setType = SetType.DROP_SET, reps = 10, weight = kg(50)),
                        ),
                    ),
                ),
            ).getOrThrow().id

            val (started, _) = startFrom(templateId)

            val sets = sessions.getSets(sessions.getExercises(started.sessionId).first().first().id).first()
            assertEquals(
                listOf(SetType.WARMUP, SetType.NORMAL, SetType.NORMAL, SetType.DROP_SET),
                sets.map(WorkoutSet::setType),
            )
            assertEquals(listOf(kg(40), kg(80), kg(85), kg(50)), sets.map(WorkoutSet::weight))
            assertEquals(listOf(12, 5, 5, 10), sets.map(WorkoutSet::reps))
        }

    @Test
    fun `the sets laid out from the template are sets the validator accepts`() =
        runTest {
            val catalogue = Catalogue.repository()
            val templateId = pushDay()
            val (started, _) = startFrom(templateId)
            val exercises = sessions.getExercises(started.sessionId).first()

            val prefilled = exercises.flatMap { exercise ->
                val type = catalogue.exercise(exercise.exerciseId).type
                sessions.getSets(exercise.id).first().map { it to type }
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
    fun `the session's exercises keep template order even when the store loses it`() =
        runTest {
            // The composition's weakest link: the session's exercise order comes from the
            // order the plan arrives in, so a store that stopped ordering by position would
            // reorder the user's workout — silently, since every id is still present.
            // ApplyTemplateUseCase's sort is what stops that, and this is where it earns its
            // keep on the way into a session.
            val templateId = pushDay()
            templates.honoursPositionOrder = false

            val (started, exercises) = startFrom(templateId)

            assertEquals(
                listOf(Catalogue.benchPress.id, Catalogue.plank.id),
                exercises.map(TemplateExercise::exerciseId),
            )
            assertEquals(
                listOf(Catalogue.benchPress.id, Catalogue.plank.id),
                sessions.getExercises(started.sessionId).first().map { it.exerciseId },
            )
        }

    @Test
    fun `a template's supersets become the session's supersets`() =
        runTest {
            // The grouping is an ordinal within one list, so it only survives the trip
            // because the whole list travels together and in order. This is the wiring
            // neither use case owns: the plan carries the group, and the caller copies it
            // onto the rows it writes.
            val templateId = createTemplate(
                name = "Superset day",
                exercises = listOf(
                    draft(exerciseId = Catalogue.benchPress.id, supersetGroup = 0),
                    draft(exerciseId = Catalogue.plank.id, supersetGroup = 0),
                ),
            ).getOrThrow().id

            val (started, plan) = startFrom(templateId)

            assertEquals(listOf(0, 0), plan.map(TemplateExercise::supersetGroup))
            val exercises = sessions.getExercises(started.sessionId).first()
            assertEquals(listOf(0, 0), exercises.map { it.supersetGroup })
            // And the pair is one superset in the session too: no rest between them.
            assertTrue(Supersets.validate(exercises.map { it.supersetGroup }).isValid)
            assertFalse(Supersets.isRestDueAfter(exercises.map { it.supersetGroup }, 0))
        }

    @Test
    fun `a template with no supersets makes a session with none`() =
        runTest {
            val templateId = pushDay()

            val (started, _) = startFrom(templateId)

            val exercises = sessions.getExercises(started.sessionId).first()
            assertEquals(listOf(null, null), exercises.map { it.supersetGroup })
        }

    @Test
    fun `applying a template that is gone starts no session`() =
        runTest {
            // The two use cases being separate is what makes this recoverable: a template
            // deleted on another screen refuses the read, and nothing has been written yet.
            val result = applyTemplate(templateId = 404L)

            assertEquals(TemplateError.NotFound(404L), result.exceptionOrNull())
            assertEquals(SessionState.Idle, state.current)
            assertNull(sessions.getActive().first())
        }

    @Test
    fun `applying writes nothing, so the session is what starts the workout`() =
        runTest {
            val templateId = pushDay()
            val writesAfterCreate = templates.writeCount

            applyTemplate(templateId).getOrThrow()

            assertEquals(writesAfterCreate, templates.writeCount)
            assertEquals(SessionState.Idle, state.current)
            assertNull(sessions.getActive().first())
        }

    @Test
    fun `a second session cannot be started from the same template while the first runs`() =
        runTest {
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
