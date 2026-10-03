package com.titan.feature.session.newsession

import androidx.lifecycle.SavedStateHandle
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.usecase.ApplyTemplateUseCase
import com.titan.core.domain.usecase.DiscardSessionUseCase
import com.titan.core.domain.usecase.GetTemplateSummariesUseCase
import com.titan.core.domain.usecase.ResumeSessionUseCase
import com.titan.core.domain.usecase.SessionLifecycleLock
import com.titan.core.domain.usecase.StartPlannedSessionUseCase
import com.titan.core.domain.usecase.StartSessionUseCase
import com.titan.feature.session.active.FakeDraftStore
import com.titan.feature.session.afterProcessDeath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The new-session flow's ViewModel, against in-memory stores.
 *
 * The assertions worth having here are about **what was written**, not about what was
 * rendered — that is the screen test's job. The flow's whole contract is that nothing is
 * written until START, and every one of these either checks that nothing was, or checks that
 * what was is the session the user asked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionViewModelTest {

    private val templates = FakeTemplateStore()
    private val sessions = FakeSessionStore()
    private val sessionState = FakeSessionStateStore()
    private val settings = FakeSettings()
    private val drafts = FakeDraftStore()

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so a launched read has run by the
        // time the call that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── Naming the session before it starts ────────────────────────────────────────────

    @Test
    fun `a hand-picked session can be named before it is started`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.NameEditRequested)
            viewModel.onEvent(NewSessionEvent.NameChanged("Leg day"))
            viewModel.onEvent(NewSessionEvent.NameConfirmed)
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            assertEquals("Leg day", sessions.storedSessions().single().name)
        }

    @Test
    fun `the name dialog opens on whatever the plan is called`() =
        runTest {
            // A template start seeds it, so this is the name the user is editing rather than a
            // blank field over a session that already has one.
            val viewModel = viewModel()
            val templateId = seedPushDay()
            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            viewModel.onEvent(NewSessionEvent.NameEditRequested)

            assertEquals("Push day", viewModel.uiState.value.nameDraft)
        }

    @Test
    fun `cancelling the name dialog leaves the plan alone`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.NameEditRequested)
            viewModel.onEvent(NewSessionEvent.NameChanged("Leg day"))
            viewModel.onEvent(NewSessionEvent.NameEditCancelled)
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            assertNull(viewModel.uiState.value.nameDraft)
            assertNull(sessions.storedSessions().single().name)
        }

    @Test
    fun `a blank name leaves the session genuinely unnamed`() =
        runTest {
            // The rule is `WorkoutSession.normalizeName`'s, asked for rather than restated: the plan
            // keeps what the user typed, and the two places that have to agree about what it means —
            // the use case on the way to the store, and the review panel on the way to the screen —
            // both go through that one function.
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.NameEditRequested)
            viewModel.onEvent(NewSessionEvent.NameChanged("   "))
            viewModel.onEvent(NewSessionEvent.NameConfirmed)
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            assertNull(sessions.storedSessions().single().name)
        }

    @Test
    fun `clearing the name puts the panel back on its placeholder`() =
        runTest {
            // The dialog's own hint invites it — "leave it empty to go by the date instead" — and
            // the panel draws its placeholder for a null. An empty string is not one, so without
            // the same normalisation the title renders as blank space with no way back to it.
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))
            viewModel.onEvent(NewSessionEvent.NameEditRequested)
            viewModel.onEvent(NewSessionEvent.NameChanged("Leg day"))
            viewModel.onEvent(NewSessionEvent.NameConfirmed)

            viewModel.onEvent(NewSessionEvent.NameEditRequested)
            viewModel.onEvent(NewSessionEvent.NameChanged(""))
            viewModel.onEvent(NewSessionEvent.NameConfirmed)

            assertNull(viewModel.readyToStart().name)
        }

    // ─── Starting from a template ───────────────────────────────────────────────────────

    @Test
    fun `selecting a template goes straight to ready-to-start`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            val step = viewModel.readyToStart()
            assertEquals("Push day", step.name)
            assertEquals(listOf("Bench press", "Incline press", "Cable fly"), step.exerciseNames())
        }

    @Test
    fun `a template's supersets are labelled as the group they are`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            assertEquals(
                listOf(null, "A1", "A2"),
                viewModel.readyToStart().exercises.map(PlannedExerciseRow::supersetLabel),
            )
        }

    @Test
    fun `selecting a template writes nothing`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            assertEquals(emptyList<Any>(), sessions.storedSessions())
            assertEquals(SessionState.Idle, sessionState.current)
        }

    @Test
    fun `starting from a template lays out its exercises, name and supersets`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            val sessionId = viewModel.uiState.value.openSessionId!!
            val session = sessions.storedSessions().single()
            assertEquals("Push day", session.name)
            assertEquals(templateId, session.templateId)

            val laidOut = sessions.storedExercises(sessionId)
            assertEquals(listOf(BENCH, INCLINE, FLY), laidOut.map(SessionExercise::exerciseId))
            assertEquals(listOf(0, 1, 2), laidOut.map(SessionExercise::position))
            assertEquals(listOf(null, 0, 0), laidOut.map(SessionExercise::supersetGroup))
        }

    @Test
    fun `starting from a template lays out every set it plans`() =
        runTest {
            // The bug the plan-as-a-list refactor fixes: a template of ten sets used to start a
            // workout with three exercises and nothing to tick off.
            val templateId = seedPushDay()
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            val sessionId = viewModel.uiState.value.openSessionId!!
            val byExercise = sessions.plannedSets.groupBy(WorkoutSet::sessionExerciseId)
            assertEquals(
                listOf(4, 3, 3),
                sessions.storedExercises(sessionId).map { byExercise[it.id].orEmpty().size },
            )
            // Planned, not performed, and numbered from 1 within each exercise.
            assertTrue(sessions.plannedSets.none(WorkoutSet::isCompleted))
            assertEquals(listOf(1, 2, 3, 4), byExercise.values.first().map(WorkoutSet::setNumber))
        }

    @Test
    fun `the weights and reps a template plans arrive in the workout`() =
        runTest {
            val templateId = templates.seed(
                name = "Ramped bench",
                drafts = listOf(
                    TemplateExerciseDraft(
                        exerciseId = BENCH,
                        sets = listOf(
                            PlannedSet(setType = SetType.WARMUP, reps = 12, weight = 40_000_000),
                            PlannedSet(reps = 5, weight = 80_000_000),
                            PlannedSet(setType = SetType.DROP_SET, reps = 10, weight = 50_000_000),
                        ),
                    ),
                ),
            )
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            assertEquals(
                listOf(SetType.WARMUP, SetType.NORMAL, SetType.DROP_SET),
                sessions.plannedSets.map(WorkoutSet::setType),
            )
            assertEquals(listOf(40_000_000L, 80_000_000L, 50_000_000L), sessions.plannedSets.map(WorkoutSet::weight))
            assertEquals(listOf(12, 5, 10), sessions.plannedSets.map(WorkoutSet::reps))
        }

    @Test
    fun `a session built by picking exercises starts with no sets in it`() =
        runTest {
            // Nothing planned it, so there is nothing to lay out — the lifter types the first set.
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            assertTrue(sessions.plannedSets.isEmpty())
        }

    @Test
    fun `starting from a template records which template line each exercise came from`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            assertEquals(
                templates.exerciseRowIds(templateId),
                sessions.allStoredExercises().map(SessionExercise::templateExerciseId),
            )
        }

    @Test
    fun `a template that cannot be read says so and stays on the list`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.TemplateSelected(404L))

            assertEquals(NewSessionMessage.TEMPLATE_UNAVAILABLE, viewModel.uiState.value.message)
            assertEquals(NewSessionStep.TemplateSelection, viewModel.uiState.value.step)
        }

    // ─── Starting from scratch ──────────────────────────────────────────────────────────

    @Test
    fun `opening the picker puts the screen in exercise selection`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.ExerciseSelectionOpened)

            assertEquals(NewSessionStep.ExerciseSelection, viewModel.uiState.value.step)
        }

    @Test
    fun `the picker's answer becomes the session, in the order it was picked`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.ExerciseSelectionOpened)
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(FLY, BENCH)))

            val step = viewModel.readyToStart()
            assertEquals(listOf("Cable fly", "Bench press"), step.exerciseNames())
            // Built by hand, so genuinely unnamed and with nothing to pace.
            assertNull(step.name)
            assertNull(step.estimatedDuration)
        }

    @Test
    fun `starting from scratch leaves the session unnamed and untemplated`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH, FLY)))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            val session = sessions.storedSessions().single()
            assertNull(session.name)
            assertNull(session.templateId)
            assertEquals(listOf(BENCH, FLY), sessions.allStoredExercises().map(SessionExercise::exerciseId))
        }

    @Test
    fun `backing out of the picker returns to the template list`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.ExerciseSelectionOpened)
            // How a cancel actually arrives: the picker's confirm bar is disabled until something
            // is ticked, so leaving it any other way brings no answer back at all. The screen
            // learns it is over from being on top again.
            viewModel.onEvent(NewSessionEvent.ExerciseSelectionClosed)

            // Not a ready-to-start panel with nothing on it, and — the part that mattered on a
            // device — not a screen still waiting for a picker that has gone, with the one action
            // that reopens it disabled for the rest of the flow's life.
            assertEquals(NewSessionStep.TemplateSelection, viewModel.uiState.value.step)
        }

    @Test
    fun `an empty answer from the picker is the template list too`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onEvent(NewSessionEvent.ExerciseSelectionOpened)
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(emptyList()))

            assertEquals(NewSessionStep.TemplateSelection, viewModel.uiState.value.step)
        }

    // ─── Correcting the plan ────────────────────────────────────────────────────────────

    @Test
    fun `dropping an exercise leaves the rest in order`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH, INCLINE, FLY)))

            viewModel.onEvent(NewSessionEvent.ExerciseRemoved(1))

            assertEquals(listOf("Bench press", "Cable fly"), viewModel.readyToStart().exerciseNames())
        }

    @Test
    fun `dropping one of a pair dissolves the superset it leaves behind`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            // Index 2 is the second half of the superset formed by 1 and 2.
            viewModel.onEvent(NewSessionEvent.ExerciseRemoved(2))

            val labels = viewModel.readyToStart().exercises.map(PlannedExerciseRow::supersetLabel)
            assertEquals(listOf(null, null), labels)
        }

    @Test
    fun `dropping an exercise drops the estimate with it`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))
            assertNotNull(viewModel.readyToStart().estimatedDuration)

            viewModel.onEvent(NewSessionEvent.ExerciseRemoved(0))

            // The figure priced the template, and the drafts carry no set counts to reprice it
            // from. Keeping it would have the panel read "2 exercises · ~30m" over a plan whose
            // 30 minutes was for three — the one number on the screen a lifter paces against.
            assertNull(viewModel.readyToStart().estimatedDuration)
        }

    @Test
    fun `an exercise deleted from the catalogue is cut from the plan, not just from the list`() =
        runTest {
            val templateId = seedPushDay()
            // The catalogue as it is by the time the template is opened: the middle exercise
            // has been deleted from the library since it was saved.
            val viewModel = viewModel(catalogue = FakeExerciseCatalogue(CATALOGUE - INCLINE_ROW))

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            val step = viewModel.readyToStart()
            assertEquals(listOf("Bench press", "Cable fly"), step.exerciseNames())
            // Said rather than silently shortened: this is not the workout whose name was
            // tapped, and finding that out halfway through it is too late to choose again.
            assertEquals(NewSessionMessage.EXERCISES_UNAVAILABLE, viewModel.uiState.value.message)
        }

    @Test
    fun `removing from a plan with a deleted exercise removes the one that was tapped`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel(catalogue = FakeExerciseCatalogue(CATALOGUE - INCLINE_ROW))
            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            // Index 1 of the panel is "Cable fly" — index 2 of the template, which still holds the
            // unresolvable draft at 1. Dropping the row from the panel alone would leave the two
            // numbered differently and remove "Bench press" here.
            viewModel.onEvent(NewSessionEvent.ExerciseRemoved(1))

            assertEquals(listOf("Bench press"), viewModel.readyToStart().exerciseNames())
        }

    @Test
    fun `a plan with a deleted exercise starts without it rather than failing`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel(catalogue = FakeExerciseCatalogue(CATALOGUE - INCLINE_ROW))
            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            // `session_exercise.exercise_id` is a foreign key, so carrying the unresolvable draft
            // into the write aborts the whole layout — with START offered, since the button reads
            // the visible list. Cut from the plan, what is written is what was reviewed.
            val sessionId = viewModel.uiState.value.openSessionId!!
            assertEquals(listOf(BENCH, FLY), sessions.storedExercises(sessionId).map(SessionExercise::exerciseId))
        }

    @Test
    fun `a plan whose exercises have all been deleted says so and stays on the list`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel(catalogue = FakeExerciseCatalogue(emptyList()))

            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            assertEquals(NewSessionMessage.EXERCISES_UNAVAILABLE, viewModel.uiState.value.message)
            // A review panel with no rows on it is a dead end whose START could not be kept.
            assertEquals(NewSessionStep.TemplateSelection, viewModel.uiState.value.step)
        }

    @Test
    fun `dropping the last exercise returns to the template list`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.ExerciseRemoved(0))

            assertEquals(NewSessionStep.TemplateSelection, viewModel.uiState.value.step)
        }

    @Test
    fun `discarding the plan returns to the template list and writes nothing`() =
        runTest {
            val templateId = seedPushDay()
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.TemplateSelected(templateId))

            viewModel.onEvent(NewSessionEvent.PlanDiscarded)

            assertEquals(NewSessionStep.TemplateSelection, viewModel.uiState.value.step)
            assertEquals(emptyList<Any>(), sessions.storedSessions())
            assertEquals(emptyList<Any>(), sessions.allStoredExercises())
            assertEquals(SessionState.Idle, sessionState.current)
        }

    // ─── Committing ─────────────────────────────────────────────────────────────────────

    @Test
    fun `starting moves the state machine to active`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            val sessionId = viewModel.uiState.value.openSessionId!!
            assertEquals(sessionId, (sessionState.current as SessionState.Active).sessionId)
        }

    @Test
    fun `pressing start twice opens one session`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.SessionStartRequested)
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            // The second press is swallowed rather than refused: without the guard it would
            // reach the use case and come back as "a session is already running", naming the
            // session the first press had just opened — an error the user did nothing to earn.
            assertEquals(1, sessions.storedSessions().size)
            assertNull(viewModel.uiState.value.message)
        }

    @Test
    fun `starting while a session is already running offers that session back`() =
        runTest {
            val running = sessions.insert(
                WorkoutSession(
                    name = "Push day",
                    isInProgress = true,
                    lastActiveAt = 1L,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            sessions.seedSets(running, count = 11)
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            // Named rather than described: the workout in the way is the only thing that can be
            // done about the refusal, and nothing else in the app can reach it.
            assertEquals(running, viewModel.uiState.value.runningWorkout?.sessionId)
            assertNull(viewModel.uiState.value.message)
            assertNull(viewModel.uiState.value.openSessionId)
            // What it is and what is in it, which is what the offer has to show for the question
            // to be answerable — see `RunningWorkout`.
            assertEquals("Push day", viewModel.uiState.value.runningWorkout?.name)
            assertEquals(11, viewModel.uiState.value.runningWorkout?.loggedSets)
            // Refused, not half-done: nothing was written over it, and START is pressable again.
            assertEquals(listOf(running), sessions.storedSessions().map { it.id })
            assertFalse(viewModel.uiState.value.isStarting)
        }

    @Test
    fun `picking the running session back up opens it`() =
        runTest {
            val running = sessions.insert(
                WorkoutSession(isInProgress = true, lastActiveAt = 1L, createdAt = 1L, updatedAt = 1L),
            )
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            viewModel.onEvent(NewSessionEvent.ResumeRequested)

            // The same landing as a start, and still no second session written: the plan on
            // screen is dropped with the flow, which is what it was always going to be until the
            // running workout is closed out.
            assertEquals(running, viewModel.uiState.value.openSessionId)
            assertNull(viewModel.uiState.value.runningWorkout)
            assertTrue(viewModel.uiState.value.isStarting)
            assertEquals(listOf(running), sessions.storedSessions().map { it.id })
        }

    @Test
    fun `discarding the running workout starts the planned one in its place`() =
        runTest {
            // In progress on disk with the state machine none the wiser, which is the case that
            // strands somebody here: a process restart leaves exactly this behind.
            sessions.insert(
                WorkoutSession(isInProgress = true, lastActiveAt = 1L, createdAt = 1L, updatedAt = 1L),
            )
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            viewModel.onEvent(NewSessionEvent.RunningSessionDiscarded)

            // The workout in the way is gone, and the one the user asked for is the only one left.
            val started = sessions.storedSessions().single()
            assertTrue(started.isInProgress)
            assertEquals(started.id, viewModel.uiState.value.openSessionId)
            assertNull(viewModel.uiState.value.runningWorkout)
            assertEquals(listOf(BENCH), sessions.storedExercises(started.id).map { it.exerciseId })
        }

    @Test
    fun `a discard that fails leaves the offer up`() =
        runTest {
            val running = sessions.insert(
                WorkoutSession(isInProgress = true, lastActiveAt = 1L, createdAt = 1L, updatedAt = 1L),
            )
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)
            sessions.deleteFailure = IOException("the store is gone")

            viewModel.onEvent(NewSessionEvent.RunningSessionDiscarded)

            assertEquals(NewSessionMessage.DISCARD_FAILED, viewModel.uiState.value.message)
            // Still offered, because the workout is still in the way — and nothing was started
            // over the top of it.
            assertEquals(running, viewModel.uiState.value.runningWorkout?.sessionId)
            assertNull(viewModel.uiState.value.openSessionId)
            assertFalse(viewModel.uiState.value.isStarting)
            assertEquals(listOf(running), sessions.storedSessions().map { it.id })
        }

    @Test
    fun `declining the offer leaves the plan where it was`() =
        runTest {
            sessions.insert(
                WorkoutSession(isInProgress = true, lastActiveAt = 1L, createdAt = 1L, updatedAt = 1L),
            )
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))
            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            viewModel.onEvent(NewSessionEvent.ResumeDismissed)

            // Nothing is opened and nothing is lost: START asks again, and is refused again until
            // the running workout is ended.
            assertNull(viewModel.uiState.value.openSessionId)
            assertNull(viewModel.uiState.value.runningWorkout)
            assertTrue(viewModel.uiState.value.step is NewSessionStep.ReadyToStart)
        }

    @Test
    fun `a start that fails part way leaves no session behind`() =
        runTest {
            sessions.layoutFailure = IOException("disk full")
            val viewModel = viewModel()
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH)))

            viewModel.onEvent(NewSessionEvent.SessionStartRequested)

            assertEquals(NewSessionMessage.START_FAILED, viewModel.uiState.value.message)
            assertEquals(emptyList<Any>(), sessions.storedSessions())
            assertEquals(SessionState.Idle, sessionState.current)
        }

    // ─── The template list ──────────────────────────────────────────────────────────────

    @Test
    fun `the template list carries the figures its rows show`() =
        runTest {
            seedPushDay()
            val viewModel = viewModel()

            val summary = viewModel.uiState.value.templates.single()
            assertEquals("Push day", summary.template.name)
            assertEquals(TemplateCategory.STRENGTH, summary.template.category)
            assertEquals(3, summary.exerciseCount)
            assertTrue(summary.estimatedDuration.inWholeMinutes > 0)
        }

    @Test
    fun `no templates is a settled state, not a loading one`() =
        runTest {
            val viewModel = viewModel()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(emptyList<Any>(), viewModel.uiState.value.templates)
        }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    // ─── Process death (#183) ───────────────────────────────────────────────────────────

    @Test
    fun `a plan assembled but not started comes back after process death`() =
        runTest {
            val handle = SavedStateHandle()
            val before = viewModel(savedState = handle)
            before.onEvent(NewSessionEvent.ExerciseSelectionOpened)
            before.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH, INCLINE, FLY)))
            before.onEvent(NewSessionEvent.NameEditRequested)
            before.onEvent(NewSessionEvent.NameChanged("Chest, heavy"))
            before.onEvent(NewSessionEvent.NameConfirmed)

            val restored = viewModel(savedState = handle.afterProcessDeath())

            val step = restored.readyToStart()
            assertEquals("Chest, heavy", step.name)
            assertEquals(3, step.exercises.size)
        }

    @Test
    fun `an exercise deleted while the process was dead is cut from the restored plan`() =
        runTest {
            val handle = SavedStateHandle()
            val before = viewModel(savedState = handle)
            before.onEvent(NewSessionEvent.ExerciseSelectionOpened)
            before.onEvent(NewSessionEvent.ExercisesSelected(listOf(BENCH, INCLINE)))

            // The catalogue the restored screen re-adopts against no longer holds the second.
            val restored = viewModel(
                catalogue = FakeExerciseCatalogue(CATALOGUE.filter { it.id != INCLINE }),
                savedState = handle.afterProcessDeath(),
            )

            assertEquals(1, restored.readyToStart().exercises.size)
            assertEquals(NewSessionMessage.EXERCISES_UNAVAILABLE, restored.uiState.value.message)
        }

    /**
     * A ViewModel with its state being collected.
     *
     * `uiState` is a `stateIn(WhileSubscribed)`, so without a collector the flow never starts
     * and the state stays at its initial value — which would make most of the assertions here
     * pass for the wrong reason. The same is true of the template list inside it, which is
     * what the estimate on a ready-to-start panel is read from.
     */
    private fun TestScope.viewModel(
        catalogue: ExerciseRepository = FakeExerciseCatalogue(CATALOGUE),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): NewSessionViewModel {
        val viewModel = NewSessionViewModel(
            savedStateHandle = savedState,
            getTemplateSummaries = GetTemplateSummariesUseCase(templates, settings, catalogue),
            applyTemplate = ApplyTemplateUseCase(templates),
            startPlannedSession = StartPlannedSessionUseCase(
                startSession = StartSessionUseCase(
                    sessionRepository = sessions,
                    sessionStateRepository = sessionState,
                    lock = SessionLifecycleLock(),
                ),
                sessionRepository = sessions,
                sessionStateRepository = sessionState,
            ),
            resumeSession = ResumeSessionUseCase(
                sessionRepository = sessions,
                sessionStateRepository = sessionState,
                lock = SessionLifecycleLock(),
            ),
            discardSession = DiscardSessionUseCase(
                sessionRepository = sessions,
                sessionStateRepository = sessionState,
                inProgressSessionRepository = drafts,
                lock = SessionLifecycleLock(),
            ),
            exerciseRepository = catalogue,
            sessionRepository = sessions,
        )
        // Unconfined *on the test scheduler*, so the flow is started and has emitted by the
        // time this returns — `backgroundScope`'s own dispatcher would queue the collection
        // behind the assertions.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    /** A template whose second and third exercises are a superset. */
    private fun seedPushDay(): Long =
        templates.seed(
            name = "Push day",
            drafts = listOf(
                TemplateExerciseDraft(exerciseId = BENCH, sets = List(4) { PlannedSet() }),
                TemplateExerciseDraft(exerciseId = INCLINE, sets = List(3) { PlannedSet() }, supersetGroup = 0),
                TemplateExerciseDraft(exerciseId = FLY, sets = List(3) { PlannedSet() }, supersetGroup = 0),
            ),
            category = TemplateCategory.STRENGTH,
        )

    /** The step the flow is on, or a failure naming what it was on instead. */
    private fun NewSessionViewModel.readyToStart(): NewSessionStep.ReadyToStart =
        uiState.value.step as? NewSessionStep.ReadyToStart
            ?: error("expected ready-to-start, was ${uiState.value.step}")

    private fun NewSessionStep.ReadyToStart.exerciseNames(): List<String> = exercises.map { it.exercise.name }

    private companion object {
        const val BENCH = 1L
        const val INCLINE = 2L
        const val FLY = 3L

        val INCLINE_ROW = Exercise(id = INCLINE, name = "Incline press")

        val CATALOGUE = listOf(
            Exercise(id = BENCH, name = "Bench press"),
            INCLINE_ROW,
            Exercise(id = FLY, name = "Cable fly"),
        )
    }
}
