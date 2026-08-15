package com.opengym.feature.history.sessions

import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
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
