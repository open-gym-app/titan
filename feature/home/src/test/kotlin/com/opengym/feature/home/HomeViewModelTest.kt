package com.opengym.feature.home

import com.opengym.core.domain.model.ActiveExercise
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SessionLifecycleLock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * What Home makes of the stores behind it.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow never starts and the state stays at its initial value,
 * which would make every assertion here pass for the wrong reason.
 *
 * The clock is pinned to a fixed Wednesday, which is what makes "today", the elapsed clock and
 * the week assertable at all. The three dates below are chosen so the week's arithmetic holds
 * **in every locale**: Monday and Wednesday are in the same week whether the locale opens one
 * on Sunday or on Monday, and the Saturday before them is outside it either way. A test that
 * assumed Monday would pass in London and fail in New York.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val sessions = FakeSessionStore()
    private val drafts = FakeDraftStore()
    private val catalogue = FakeExerciseCatalogue(listOf(BENCH_PRESS))
    private val templates = FakeTemplateStore()
    private val state = FakeSessionStateStore()
    private val clock = FixedClock(middayOn(TODAY))

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── The resume banner ──────────────────────────────────────────────────────────────

    @Test
    fun `nothing in progress leaves the banner off`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, TODAY)))

        val home = viewModel().uiState.value

        assertNull(home.resume)
        assertFalse(home.isLoading)
    }

    @Test
    fun `an open session becomes a banner with its name, clock, position and cost`() = runTest {
        val startedAt = clock.now - 23.minutes.inWholeMilliseconds
        sessions.setSessions(listOf(openSession(7, startedAt = startedAt)))
        sessions.seedSets(sessionId = 7, count = 11)
        drafts.save(draft(sessionId = 7, startedAt = startedAt, completed = 2, planned = 4))

        val resume = requireNotNull(viewModel().uiState.value.resume)

        assertEquals(7L, resume.sessionId)
        assertEquals("Push Day", resume.name)
        assertEquals(23.minutes, resume.elapsed)
        assertEquals(11, resume.loggedSets)
        assertEquals(
            SessionPosition(exerciseName = "Bench press", setNumber = 3, setCount = 4),
            resume.position,
        )
        assertFalse(resume.isPaused)
    }

    @Test
    fun `a paused workout says so, and its clock stops where it was paused`() = runTest {
        val startedAt = clock.now - 30.minutes.inWholeMilliseconds
        sessions.setSessions(listOf(openSession(7, startedAt = startedAt)))
        drafts.save(
            draft(sessionId = 7, startedAt = startedAt)
                .copy(pausedAt = startedAt + 8.minutes.inWholeMilliseconds),
        )

        val resume = requireNotNull(viewModel().uiState.value.resume)

        assertTrue(resume.isPaused)
        // Eight minutes in, whatever the wall clock has done in the twenty-two since.
        assertEquals(8.minutes, resume.elapsed)
    }

    @Test
    fun `a session with no draft is still offered, timed from its own row`() = runTest {
        // The workout the process died under before anything was written to the draft slot.
        sessions.setSessions(listOf(openSession(7, startedAt = clock.now - 5.minutes.inWholeMilliseconds)))

        val resume = requireNotNull(viewModel().uiState.value.resume)

        assertEquals(5.minutes, resume.elapsed)
        assertNull(resume.position)
    }

    @Test
    fun `a draft belonging to another session is not read onto this one`() = runTest {
        sessions.setSessions(listOf(openSession(7)))
        drafts.save(draft(sessionId = 99, completed = 2, planned = 4))

        assertNull(requireNotNull(viewModel().uiState.value.resume).position)
    }

    @Test
    fun `an exercise deleted from the library drops the position rather than blanking it`() = runTest {
        sessions.setSessions(listOf(openSession(7)))
        drafts.save(draft(sessionId = 7, exerciseId = 404L))

        assertNull(requireNotNull(viewModel().uiState.value.resume).position)
    }

    // ─── Discarding ─────────────────────────────────────────────────────────────────────

    @Test
    fun `discarding is asked about before anything is deleted`() = runTest {
        sessions.setSessions(listOf(openSession(7)))
        val viewModel = viewModel()

        viewModel.onEvent(HomeEvent.DiscardRequested)

        assertTrue(viewModel.uiState.value.confirmingDiscard)
        assertEquals(listOf(7L), sessions.storedSessions().map(WorkoutSession::id))
    }

    @Test
    fun `confirming the discard deletes the session, clears the draft and drops the banner`() = runTest {
        sessions.setSessions(listOf(openSession(7)))
        drafts.save(draft(sessionId = 7))
        val viewModel = viewModel()

        viewModel.onEvent(HomeEvent.DiscardRequested)
        viewModel.onEvent(HomeEvent.DiscardConfirmed)

        val home = viewModel.uiState.value
        assertNull(home.resume)
        assertFalse(home.confirmingDiscard)
        assertEquals(emptyList<WorkoutSession>(), sessions.storedSessions())
        assertEquals(1, drafts.clearCount)
        assertEquals(SessionState.Idle, state.current)
    }

    @Test
    fun `a discard reached after a process restart still finds the session to delete`() = runTest {
        // The case Home exists for: the row says in progress, the in-memory state machine has
        // been reset to Idle by the restart. Without the resume that precedes the discard,
        // `DiscardSessionUseCase` has no open session and refuses.
        sessions.setSessions(listOf(openSession(7)))
        val viewModel = viewModel(state = FakeSessionStateStore(SessionState.Idle))

        viewModel.onEvent(HomeEvent.DiscardConfirmed)

        assertEquals(emptyList<WorkoutSession>(), sessions.storedSessions())
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `a discard that fails leaves the workout where it is and says so`() = runTest {
        sessions.setSessions(listOf(openSession(7)))
        sessions.deleteFailure = IllegalStateException("the store is gone")
        val viewModel = viewModel()

        viewModel.onEvent(HomeEvent.DiscardRequested)
        viewModel.onEvent(HomeEvent.DiscardConfirmed)

        val home = viewModel.uiState.value
        assertEquals(HomeMessage.DISCARD_FAILED, home.message)
        assertEquals(listOf(7L), sessions.storedSessions().map(WorkoutSession::id))
        // The banner is still up, because the workout is still there and still in the way.
        assertEquals(7L, home.resume?.sessionId)
        assertFalse(home.isDiscarding)
    }

    @Test
    fun `a message is dropped once it has been shown`() = runTest {
        sessions.setSessions(listOf(openSession(7)))
        sessions.deleteFailure = IllegalStateException("the store is gone")
        val viewModel = viewModel()

        viewModel.onEvent(HomeEvent.DiscardConfirmed)
        viewModel.onEvent(HomeEvent.MessageShown)

        assertNull(viewModel.uiState.value.message)
    }

    // ─── This week ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the week marks the days trained and counts them`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, MONDAY),
                loggedSession(2, TODAY),
                // Outside the week, whichever day the locale opens one on.
                loggedSession(3, LAST_SATURDAY),
            ),
        )

        val week = requireNotNull(viewModel().uiState.value.week)

        assertEquals(7, week.days.size)
        assertEquals(2, week.sessionCount)
        assertEquals(1, week.days.single { it.date == MONDAY }.sessionCount)
        assertEquals(1, week.days.single { it.date == TODAY }.sessionCount)
        assertTrue(week.days.none { it.date == LAST_SATURDAY })
        assertTrue(week.days.single { it.date == TODAY }.isToday)
    }

    @Test
    fun `two sessions on one day count twice on one cell`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, TODAY), loggedSession(2, TODAY)))

        val week = requireNotNull(viewModel().uiState.value.week)

        assertEquals(2, week.sessionCount)
        assertEquals(2, week.days.single { it.date == TODAY }.sessionCount)
    }

    @Test
    fun `a user who has never finished a session gets no week at all`() = runTest {
        // Not an empty strip over a zero — see HomeUiState.week. The open session below is
        // deliberately in the store: a workout that has not happened yet is not a week's
        // worth of training.
        sessions.setSessions(listOf(openSession(7)))

        val home = viewModel().uiState.value

        assertNull(home.week)
        assertFalse(home.isLoading)
    }

    @Test
    fun `the session still running is not counted in the week`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, TODAY), openSession(7)))

        val week = requireNotNull(viewModel().uiState.value.week)

        assertEquals(1, week.sessionCount)
    }

    // ─── Quick start ────────────────────────────────────────────────────────────────────

    @Test
    fun `the quick-start strip holds the templates, cut to what fits`() = runTest {
        val saved = (1..8).map { template(it.toLong(), "Template $it") }
        val templates = FakeTemplateStore(
            templates = saved.map { it.first },
            exercises = saved.flatMap { it.second },
        )

        val home = viewModel(templates = templates).uiState.value

        assertEquals(6, home.templates.size)
        assertEquals("Template 1", home.templates.first().template.name)
    }

    @Test
    fun `no saved templates is a real answer rather than a loading one`() = runTest {
        val home = viewModel().uiState.value

        assertEquals(emptyList<Long>(), home.templates.map { it.template.id })
        assertFalse(home.isLoading)
    }

    // ─── Rig ────────────────────────────────────────────────────────────────────────────

    private fun TestScope.viewModel(
        sessions: FakeSessionStore = this@HomeViewModelTest.sessions,
        drafts: FakeDraftStore = this@HomeViewModelTest.drafts,
        templates: FakeTemplateStore = this@HomeViewModelTest.templates,
        state: FakeSessionStateStore = this@HomeViewModelTest.state,
    ): HomeViewModel {
        // A lock of this test's own rather than the shared one, so two tests running in the
        // same JVM cannot wait on each other's transitions.
        val lock = SessionLifecycleLock()
        val viewModel = HomeViewModel(
            sessionRepository = sessions,
            inProgressSessionRepository = drafts,
            exerciseRepository = catalogue,
            getTemplateSummaries = GetTemplateSummariesUseCase(templates, FakeSettings()),
            resumeSession = ResumeSessionUseCase(sessions, state, clock, lock),
            discardSession = DiscardSessionUseCase(sessions, state, drafts, lock),
            clock = clock,
        )
        // Unconfined *on the test scheduler*, so the flow is started and has emitted by the
        // time this returns.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    /** A workout that is over: the rows the week is counted from. */
    private fun loggedSession(id: Long, date: LocalDate) = WorkoutSession(
        id = id,
        name = "Session $id",
        isInProgress = false,
        completedAt = middayOn(date) + 52.minutes.inWholeMilliseconds,
        lastActiveAt = middayOn(date),
        createdAt = middayOn(date),
        updatedAt = middayOn(date),
    )

    /** The workout the app is still holding open. */
    private fun openSession(id: Long, startedAt: Long = clock.now) = WorkoutSession(
        id = id,
        name = "Push Day",
        isInProgress = true,
        lastActiveAt = startedAt,
        createdAt = startedAt,
        updatedAt = startedAt,
    )

    /** A draft sitting on [completed] of [planned] sets of one exercise. */
    private fun draft(
        sessionId: Long,
        startedAt: Long = clock.now,
        completed: Int = 0,
        planned: Int = 0,
        exerciseId: Long = BENCH_PRESS.id,
    ) = ActiveSession(
        sessionId = sessionId,
        startedAt = startedAt,
        exercises = listOf(
            ActiveExercise(
                exerciseId = exerciseId,
                sessionExerciseId = 1L,
                sets = List(planned) { index ->
                    val done = index < completed
                    ActiveSet(isCompleted = done, completedAt = startedAt.takeIf { done })
                },
            ),
        ),
    )

    private companion object {
        /**
         * A Wednesday, and "today" for every test here.
         *
         * With [MONDAY] two days before it and [LAST_SATURDAY] four, the first two fall in the
         * same week and the third outside it under both a Sunday-opening and a Monday-opening
         * locale — which is what keeps these assertions from depending on where CI runs.
         */
        val TODAY: LocalDate = LocalDate.of(2026, 4, 29)
        val MONDAY: LocalDate = LocalDate.of(2026, 4, 27)
        val LAST_SATURDAY: LocalDate = LocalDate.of(2026, 4, 25)

        val BENCH_PRESS = Exercise(id = 1L, name = "Bench press", type = ExerciseType.STRENGTH)

        /**
         * Midday on [date] in the device's own zone.
         *
         * Local midday rather than midday UTC: what is being asserted is which *calendar day*
         * a session lands on, and that is read in the default zone — a timestamp fixed in UTC
         * would drift onto the day before in Auckland.
         */
        fun middayOn(date: LocalDate): Long =
            date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
