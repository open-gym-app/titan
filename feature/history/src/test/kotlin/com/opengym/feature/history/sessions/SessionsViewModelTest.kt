package com.opengym.feature.history.sessions

import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.usecase.CreateTemplateUseCase
import com.opengym.core.domain.usecase.DeleteTemplateUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.UpdateTemplateUseCase
import com.opengym.core.ui.component.TrainingLoad
import com.opengym.feature.history.plate.FakeUserPreferencesRepository
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
import java.time.YearMonth
import kotlin.time.Duration.Companion.minutes

/**
 * The sessions browser's ViewModel against in-memory stores.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow never starts and the state stays at its initial value,
 * which would make every assertion here pass for the wrong reason.
 *
 * The clock is pinned to a fixed day, which is what makes "today", the rolling date windows
 * and the calendar's opening month assertable at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    private val sessions = FakeSessionStore()
    private val records = FakePersonalRecordStore()
    private val templates = FakeTemplateStore()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── The unfiltered history ─────────────────────────────────────────────────────────

    @Test
    fun `sessions come out newest first, grouped by the month they started in`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, APRIL_10, name = "Legs"),
                loggedSession(2, APRIL_28, name = "Push"),
                loggedSession(3, MARCH_30, name = "Pull"),
            ),
        )

        val state = viewModel().uiState.value

        assertEquals(
            listOf(YearMonth.of(2026, 4), YearMonth.of(2026, 3)),
            state.months.map { it.month },
        )
        assertEquals(listOf("Push", "Legs"), state.months.first().sessions.map { it.name })
        assertEquals(3, state.sessionCount)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a row carries the duration off the session and the totals off the aggregate`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28, duration = 52.minutes)))
        sessions.setTotals(sessionId = 1, sets = 12, volume = 4_820_000_000)

        val row = viewModel().uiState.value.months.single().sessions.single()

        assertEquals(52.minutes, row.duration)
        assertEquals(12, row.totalSets)
        assertEquals(4_820_000_000L, row.totalVolume)
    }

    @Test
    fun `a session that logged nothing reads as zeroes rather than disappearing`() = runTest {
        // The aggregate has no row for it — see SessionRepository.getTotals. A user who
        // started a workout and abandoned it should still find it where they left it.
        sessions.setSessions(listOf(loggedSession(1, APRIL_28, name = "Gave up")))

        val row = viewModel().uiState.value.months.single().sessions.single()

        assertEquals(0, row.totalSets)
        assertEquals(0L, row.totalVolume)
    }

    @Test
    fun `a session still in progress is not history`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, APRIL_28, name = "Finished"),
                loggedSession(2, APRIL_28, name = "Running", isInProgress = true),
            ),
        )

        val state = viewModel().uiState.value

        // A row for a workout still being logged would show a duration that is not final and
        // open a recap of a session that has not happened yet.
        assertEquals(listOf("Finished"), state.months.single().sessions.map { it.name })
        assertEquals(1, state.sessionCount)
    }

    @Test
    fun `today's session is marked as today's`() = runTest {
        sessions.setSessions(
            listOf(loggedSession(1, TODAY), loggedSession(2, APRIL_10)),
        )

        val rows = viewModel().uiState.value.months.flatMap { it.sessions }

        assertTrue(rows.first { it.id == 1L }.isToday)
        assertFalse(rows.first { it.id == 2L }.isToday)
    }

    @Test
    fun `nothing is settled until the sessions read comes back`() = runTest {
        // A store that has not answered — which is not the same as an empty history. An empty
        // state flashed over a list that is still opening tells a user with four hundred
        // sessions that they have none.
        val state = viewModel(sessions = FakeSessionStore(sessions = null)).uiState.value

        assertTrue(state.isLoading)
    }

    // ─── The PR badge ───────────────────────────────────────────────────────────────────

    @Test
    fun `a record achieved inside a session's window badges that session and no other`() =
        runTest {
            val target = loggedSession(1, APRIL_28, name = "Push", duration = 60.minutes)
            sessions.setSessions(listOf(target, loggedSession(2, APRIL_10, name = "Legs")))

            val viewModel = viewModel(
                records = FakePersonalRecordStore(
                    mapOf(7L to recordAt(exerciseId = 7, achievedAt = target.createdAt + 1_000)),
                ),
            )
            val rows = viewModel.uiState.value.months.flatMap { it.sessions }

            assertTrue(rows.first { it.id == 1L }.hasRecord)
            assertFalse(rows.first { it.id == 2L }.hasRecord)
        }

    @Test
    fun `a record achieved between two sessions badges neither`() = runTest {
        val session = loggedSession(1, APRIL_28, duration = 30.minutes)
        sessions.setSessions(listOf(session))

        val viewModel = viewModel(
            records = FakePersonalRecordStore(
                // An hour after it finished — a record whose set belongs to a session this
                // list no longer holds, or to none at all.
                mapOf(7L to recordAt(exerciseId = 7, achievedAt = session.completedAt!! + 3_600_000)),
            ),
        )

        assertFalse(viewModel.uiState.value.months.single().sessions.single().hasRecord)
    }

    @Test
    fun `no records means no badges, which is every install until the engine lands`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28)))

        assertFalse(viewModel().uiState.value.months.single().sessions.single().hasRecord)
    }

    // ─── Search ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `search matches a name, ignoring case`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, APRIL_28, name = "Push Day · Upper"),
                loggedSession(2, APRIL_10, name = "Leg Day"),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.QueryChanged("push"))

        assertEquals(listOf(1L), viewModel.uiState.value.months.flatMap { it.sessions }.map { it.id })
        assertEquals(1, viewModel.uiState.value.matchCount)
        // The unfiltered count is what the header compares against, so it must not narrow.
        assertEquals(2, viewModel.uiState.value.sessionCount)
    }

    @Test
    fun `search matches a note as well as a name`() = runTest {
        // "Felt heavy, dropped the last set" is as good a handle on a workout as its name, and
        // a search that only read names would find nothing for it.
        sessions.setSessions(
            listOf(
                loggedSession(1, APRIL_28, name = "Push Day", notes = "shoulder felt tweaky"),
                loggedSession(2, APRIL_10, name = "Leg Day"),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.QueryChanged("tweaky"))

        assertEquals(listOf(1L), viewModel.uiState.value.months.flatMap { it.sessions }.map { it.id })
    }

    @Test
    fun `a blank search narrows nothing`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28), loggedSession(2, APRIL_10)))
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.QueryChanged("   "))

        assertEquals(2, viewModel.uiState.value.matchCount)
    }

    // ─── The date window ────────────────────────────────────────────────────────────────

    @Test
    fun `a rolling window includes today and the days before it`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, TODAY),
                loggedSession(2, TODAY.minusDays(6)),
                loggedSession(3, TODAY.minusDays(7)),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.DateFilterSelected(DateFilter.LastDays(7)))

        // Seven days is today and the six before it, not today and the seven before it.
        assertEquals(
            listOf(1L, 2L),
            viewModel.uiState.value.months.flatMap { it.sessions }.map { it.id },
        )
    }

    @Test
    fun `an explicit window includes both of its ends`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, APRIL_28),
                loggedSession(2, APRIL_10),
                loggedSession(3, MARCH_30),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(
            SessionsEvent.DateFilterSelected(DateFilter.Between(MARCH_30, APRIL_10)),
        )

        assertEquals(
            listOf(2L, 3L),
            viewModel.uiState.value.months.flatMap { it.sessions }.map { it.id },
        )
    }

    @Test
    fun `a window handed back the wrong way round still matches`() = runTest {
        // A range picker can answer either way round, and a backwards range silently matches
        // nothing — which reads as a filter that broke the list.
        sessions.setSessions(listOf(loggedSession(1, APRIL_10)))
        val viewModel = viewModel()

        viewModel.onEvent(
            SessionsEvent.DateFilterSelected(DateFilter.Between(APRIL_28, MARCH_30)),
        )

        assertEquals(1, viewModel.uiState.value.matchCount)
    }

    @Test
    fun `the search and the window combine as an intersection`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, TODAY, name = "Push Day"),
                loggedSession(2, TODAY, name = "Leg Day"),
                loggedSession(3, TODAY.minusDays(20), name = "Push Day"),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.QueryChanged("push"))
        viewModel.onEvent(SessionsEvent.DateFilterSelected(DateFilter.LastDays(7)))

        // Each alone would keep two rows. Together they keep the one that satisfies both.
        assertEquals(
            listOf(1L),
            viewModel.uiState.value.months.flatMap { it.sessions }.map { it.id },
        )
    }

    @Test
    fun `clearing drops the search and the window together`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28, name = "Push")))
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.QueryChanged("nothing matches this"))
        viewModel.onEvent(SessionsEvent.DateFilterSelected(DateFilter.LastDays(7)))
        assertEquals(0, viewModel.uiState.value.matchCount)

        viewModel.onEvent(SessionsEvent.SearchAndFiltersCleared)

        assertEquals(1, viewModel.uiState.value.matchCount)
        assertFalse(viewModel.uiState.value.hasFilters)
    }

    // ─── The calendar ───────────────────────────────────────────────────────────────────

    @Test
    fun `the calendar opens on this month and marks the days that were trained`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28), loggedSession(2, APRIL_10)))
        sessions.setTotals(sessionId = 1, sets = 12, volume = 4_000_000_000)
        sessions.setTotals(sessionId = 2, sets = 6, volume = 1_000_000_000)

        val calendar = viewModel().uiState.value.calendar

        assertEquals(YearMonth.of(2026, 4), calendar.month)
        assertEquals(setOf(10, 28), calendar.days.keys)
        assertEquals(2, calendar.sessionCount)
        assertEquals(TODAY, calendar.today)
    }

    @Test
    fun `a day is heavy from half of the month's heaviest upwards`() = runTest {
        sessions.setSessions(
            listOf(
                loggedSession(1, APRIL_28),
                loggedSession(2, APRIL_10),
                loggedSession(3, APRIL_2),
            ),
        )
        sessions.setTotals(sessionId = 1, sets = 12, volume = 4_000_000_000)
        sessions.setTotals(sessionId = 2, sets = 8, volume = 2_000_000_000)
        sessions.setTotals(sessionId = 3, sets = 4, volume = 1_000_000_000)

        val days = viewModel().uiState.value.calendar.days

        assertEquals(TrainingLoad.HEAVY, days.getValue(28).load)
        // Exactly half counts as heavy, which is the boundary the ramp is defined at.
        assertEquals(TrainingLoad.HEAVY, days.getValue(10).load)
        assertEquals(TrainingLoad.LIGHT, days.getValue(2).load)
    }

    @Test
    fun `a month of nothing but cardio is light rather than uniformly heavy`() = runTest {
        // Every day's volume is zero, and `0 >= 0` would paint a rest-heavy month of running
        // as the hardest one the user has had.
        sessions.setSessions(listOf(loggedSession(1, APRIL_28), loggedSession(2, APRIL_10)))
        sessions.setTotals(sessionId = 1, sets = 5, volume = 0)
        sessions.setTotals(sessionId = 2, sets = 5, volume = 0)

        val days = viewModel().uiState.value.calendar.days

        assertTrue(days.values.all { it.load == TrainingLoad.LIGHT })
    }

    @Test
    fun `two sessions on one day are one cell that says two`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28), loggedSession(2, APRIL_28)))

        val days = viewModel().uiState.value.calendar.days

        assertEquals(1, days.size)
        assertEquals(2, days.getValue(28).sessionCount)
    }

    @Test
    fun `paging back changes the month and drops the today marker`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28), loggedSession(2, MARCH_30)))
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.CalendarMonthChanged(forward = false))

        val calendar = viewModel.uiState.value.calendar
        assertEquals(YearMonth.of(2026, 3), calendar.month)
        assertEquals(setOf(30), calendar.days.keys)
        // Today is not on this page, so there is nothing to ring.
        assertNull(calendar.today)
    }

    @Test
    fun `the calendar ignores the history's filters`() = runTest {
        // It is a way of reading a month, not a view of the current search — and its own
        // navigation is the month arrows, which a date window would silently be fighting.
        sessions.setSessions(listOf(loggedSession(1, APRIL_28, name = "Push")))
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.QueryChanged("nothing matches this"))

        assertEquals(0, viewModel.uiState.value.matchCount)
        assertEquals(1, viewModel.uiState.value.calendar.sessionCount)
    }

    @Test
    fun `tapping a day narrows the history to it and shows the history`() = runTest {
        sessions.setSessions(listOf(loggedSession(1, APRIL_28), loggedSession(2, APRIL_10)))
        val viewModel = viewModel()

        viewModel.onEvent(SessionsEvent.TabSelected(SessionsTab.CALENDAR))
        viewModel.onEvent(SessionsEvent.CalendarDaySelected(APRIL_10))

        val state = viewModel.uiState.value
        // Both halves: a filter applied to a tab the user cannot see reads as a tap that did
        // nothing, and then surprises them the next time they open the history.
        assertEquals(SessionsTab.HISTORY, state.tab)
        assertEquals(DateFilter.Between(APRIL_10, APRIL_10), state.dateFilter)
        assertEquals(listOf(2L), state.months.flatMap { it.sessions }.map { it.id })
    }

    // ─── Templates ──────────────────────────────────────────────────────────────────────

    @Test
    fun `templates arrive as summaries and are not loading once they have`() = runTest {
        val viewModel = viewModel(
            templates = FakeTemplateStore(
                templates = listOf(namedTemplate(1, "Push Day"), namedTemplate(2, "Pull Day")),
            ),
        )

        val state = viewModel.uiState.value

        assertEquals(listOf("Push Day", "Pull Day"), state.templates.map { it.template.name })
        assertFalse(state.isLoadingTemplates)
    }

    // ─── The templates tab's row actions ────────────────────────────────────────────────

    @Test
    fun `renaming keeps the template's exercises and its category`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateRenameRequested(1))
        viewModel.onEvent(SessionsEvent.TemplateNameChanged("Push Day · Heavy"))
        viewModel.onEvent(SessionsEvent.TemplateNameConfirmed)

        assertEquals(
            listOf("Push Day · Heavy", "Pull Day"),
            templates.templates().map(WorkoutTemplate::name),
        )
        assertEquals(TemplateCategory.STRENGTH, templates.templates().first().category)
        // The body survives the rename: it is handed straight back to the use case, which is
        // the only way to write a name at all.
        assertEquals(listOf(10L, 11L), templates.exercisesOf(1).map(TemplateExercise::exerciseId))
        assertNull(viewModel.uiState.value.templateAction)
    }

    @Test
    fun `the rename dialog opens on the template's own name and category`() = runTest {
        val viewModel = viewModel(templates = templateStore())

        viewModel.onEvent(SessionsEvent.TemplateRenameRequested(1))

        val naming = viewModel.uiState.value.templateAction as TemplateAction.Naming
        assertEquals(TemplateNaming.RENAME, naming.kind)
        assertEquals("Push Day", naming.name)
        assertEquals(TemplateCategory.STRENGTH, naming.category)
    }

    @Test
    fun `duplicating writes a second template with the same body`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateDuplicateRequested(1, "Push Day (copy)"))
        viewModel.onEvent(SessionsEvent.TemplateNameConfirmed)

        val copy = templates.templates().single { it.name == "Push Day (copy)" }
        assertEquals(TemplateCategory.STRENGTH, copy.category)
        assertEquals(
            listOf(10L, 11L),
            templates.exercisesOf(copy.id).map(TemplateExercise::exerciseId),
        )
        // The original is untouched, which is the whole difference from a rename.
        assertEquals(listOf(10L, 11L), templates.exercisesOf(1).map(TemplateExercise::exerciseId))
        assertEquals(SessionsMessage.TEMPLATE_DUPLICATED, viewModel.uiState.value.message)
    }

    @Test
    fun `a name already in use is reported under the field and the dialog stays up`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateDuplicateRequested(1, "Pull Day"))
        viewModel.onEvent(SessionsEvent.TemplateNameConfirmed)

        val naming = viewModel.uiState.value.templateAction as TemplateAction.Naming
        assertTrue(naming.nameTaken)
        assertEquals("Pull Day", naming.name)
        assertNull(viewModel.uiState.value.message)
        // Nothing was written: two templates went in, two are still there.
        assertEquals(2, templates.templates().size)
    }

    @Test
    fun `typing again clears the name-already-taken refusal`() = runTest {
        val viewModel = viewModel(templates = templateStore())

        viewModel.onEvent(SessionsEvent.TemplateDuplicateRequested(1, "Pull Day"))
        viewModel.onEvent(SessionsEvent.TemplateNameConfirmed)
        viewModel.onEvent(SessionsEvent.TemplateNameChanged("Pull Day · Volume"))

        val naming = viewModel.uiState.value.templateAction as TemplateAction.Naming
        assertFalse(naming.nameTaken)
    }

    @Test
    fun `deleting asks first, and the template is still there until it is confirmed`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateDeleteRequested(1))

        val deleting = viewModel.uiState.value.templateAction as TemplateAction.Deleting
        assertEquals("Push Day", deleting.name)
        assertEquals(2, templates.templates().size)
    }

    @Test
    fun `a confirmed delete removes the template and says so`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateDeleteRequested(1))
        viewModel.onEvent(SessionsEvent.TemplateDeleteConfirmed)

        assertEquals(listOf("Pull Day"), templates.templates().map(WorkoutTemplate::name))
        assertEquals(emptyList<TemplateExercise>(), templates.exercisesOf(1))
        assertEquals(SessionsMessage.TEMPLATE_DELETED, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.templateAction)
    }

    @Test
    fun `deleting a template leaves the sessions logged from it exactly where they are`() = runTest {
        // The guarantee `DeleteTemplateUseCase` makes and the delete dialog promises out loud:
        // `workout_session.template_id` is SET NULL, so the history survives the plan it was run
        // from. Asserted at this level because it is what the user is being told here.
        sessions.setSessions(
            listOf(
                loggedSession(1, APRIL_28, name = "Push Day · Upper"),
                loggedSession(2, APRIL_10, name = "Pull Day"),
            ),
        )
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateDeleteRequested(1))
        viewModel.onEvent(SessionsEvent.TemplateDeleteConfirmed)

        val history = viewModel.uiState.value.months.flatMap(SessionMonth::sessions)
        assertEquals(listOf("Push Day · Upper", "Pull Day"), history.map(HistorySession::name))
        assertEquals(2, viewModel.uiState.value.sessionCount)
    }

    @Test
    fun `acting on a template that has gone reports it rather than writing`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateDeleteRequested(1))
        // Deleted from somewhere else while the confirmation was up.
        templates.delete(1)
        viewModel.onEvent(SessionsEvent.TemplateDeleteConfirmed)

        assertEquals(SessionsMessage.TEMPLATE_GONE, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.templateAction)
    }

    @Test
    fun `a store that cannot be written closes the dialog and says so`() = runTest {
        val templates = templateStore()
        val viewModel = viewModel(templates = templates)

        viewModel.onEvent(SessionsEvent.TemplateDeleteRequested(1))
        templates.writeFailure = IllegalStateException("disk full")
        viewModel.onEvent(SessionsEvent.TemplateDeleteConfirmed)

        assertEquals(SessionsMessage.TEMPLATE_ACTION_FAILED, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.templateAction)
    }

    @Test
    fun `a row action on a template the list no longer holds does nothing`() = runTest {
        val viewModel = viewModel(templates = templateStore())

        viewModel.onEvent(SessionsEvent.TemplateRenameRequested(404))

        assertNull(viewModel.uiState.value.templateAction)
    }

    /** Two templates, the first of them with a body and a category. */
    private fun templateStore() = FakeTemplateStore(
        templates = listOf(
            namedTemplate(1, "Push Day", TemplateCategory.STRENGTH),
            namedTemplate(2, "Pull Day"),
        ),
        exercises = listOf(
            TemplateExercise(id = 100, templateId = 1, exerciseId = 10, position = 0, sets = List(4) { PlannedSet() }),
            TemplateExercise(id = 101, templateId = 1, exerciseId = 11, position = 1, sets = List(3) { PlannedSet() }),
        ),
    )

    private fun TestScope.viewModel(
        sessions: FakeSessionStore = this@SessionsViewModelTest.sessions,
        records: FakePersonalRecordStore = this@SessionsViewModelTest.records,
        templates: FakeTemplateStore = this@SessionsViewModelTest.templates,
    ): SessionsViewModel {
        val viewModel = SessionsViewModel(
            sessionRepository = sessions,
            personalRecordRepository = records,
            getTemplateSummaries = GetTemplateSummariesUseCase(
                templateRepository = templates,
                userPreferencesRepository = FakeUserPreferencesRepository(),
            ),
            templateRepository = templates,
            createTemplate = CreateTemplateUseCase(templates),
            updateTemplate = UpdateTemplateUseCase(templates),
            deleteTemplate = DeleteTemplateUseCase(templates),
            clock = FixedClock(middayOn(TODAY)),
        )
        // Unconfined *on the test scheduler*, so the flow is started and has emitted by the
        // time this returns.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    private companion object {
        /** A Tuesday, and "today" for every test here. */
        val TODAY: LocalDate = LocalDate.of(2026, 4, 28)

        val APRIL_28: LocalDate = LocalDate.of(2026, 4, 28)
        val APRIL_10: LocalDate = LocalDate.of(2026, 4, 10)
        val APRIL_2: LocalDate = LocalDate.of(2026, 4, 2)
        val MARCH_30: LocalDate = LocalDate.of(2026, 3, 30)
    }
}
