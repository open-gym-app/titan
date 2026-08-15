package com.opengym.feature.history.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.SessionTotals
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.ui.component.TrainingDay
import com.opengym.core.ui.component.TrainingLoad
import com.opengym.core.ui.format.localDateOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The sessions browser: everything logged, the templates saved, and the month as a heatmap.
 *
 * ### Three tabs, one ViewModel
 *
 * They share a state because they are not three screens — the calendar's tap sets the
 * history's filter, and the header's count is the history's count. A ViewModel each would
 * mean the calendar naming the history's filter through something above both of them, for
 * two surfaces that live in one destination.
 *
 * ### Four reads, and why none of them is per row
 *
 * The history shows a duration, a set count and a volume on every row. Only the first is a
 * function of the session's own columns; the other two are aggregates over its sets, and
 * reading those per row would be a database observer per session on a list that grows for the
 * life of the install. [SessionRepository.getTotals] is the single grouped query that answers
 * all of them at once — see [SessionTotals].
 *
 * ### Which sessions are history
 *
 * A session still in progress is **not** listed. This tab is the record of what has been
 * done: a row for a workout that is still running would show a duration that is not final and
 * open a recap of a session that has not happened yet, and the place to resume one is the
 * home screen, which is built for exactly that.
 *
 * ### Which records put a badge on a row
 *
 * A [PersonalRecord] is stamped with when it was achieved, so the records a session set are
 * the ones whose `achievedAt` falls inside its window. That is derived here rather than
 * queried, because the PR engine (#30) has not landed and `PersonalRecordRepository` exposes
 * exactly one read — the best record per exercise. Two consequences, both of which go when
 * #30 does, and both of which the session summary shares:
 *
 * - **a record that has since been beaten leaves no badge**, because only the current best
 *   is readable, so an old session that set one shows nothing;
 * - until the engine writes anything the table is empty and no row is badged, which is what
 *   #24 asks for rather than blocking on #30.
 *
 * Sessions do not overlap in time — the lifecycle use cases allow one at a time — so a
 * record's instant belongs to at most one of them and there is no ambiguity to resolve.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    personalRecordRepository: PersonalRecordRepository,
    getTemplateSummaries: GetTemplateSummariesUseCase,
    private val clock: Clock,
) : ViewModel() {

    /** What the screen owns, as opposed to what the stores do. */
    private val screenState = MutableStateFlow(ScreenState())

    /**
     * The three session reads, as one value, so the outer combine takes three arguments.
     *
     * [History.sessions] is null until the rows come back, rather than an empty list: an
     * empty history is a real answer with a call to action attached, and flashing it for the
     * few milliseconds before the first emission tells a user with four hundred sessions that
     * they have none. The other two answer for an empty store with an empty map, which is
     * both the loading value and the settled one, so neither needs the distinction.
     */
    private val history: StateFlow<History> = combine(
        sessionRepository.getAll(),
        sessionRepository.getTotals(),
        personalRecordRepository.getHeadlineRecords(),
    ) { sessions, totals, records ->
        History(sessions = sessions, totals = totals, records = records)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), History())

    /**
     * The saved templates, or **null** until the read comes back — same reason as above, and
     * the same shape the new-session picker uses for the same list.
     */
    private val templates: StateFlow<List<TemplateSummary>?> = getTemplateSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    val uiState: StateFlow<SessionsUiState> = combine(
        history,
        templates,
        screenState,
    ) { history, templates, screen ->
        buildState(history, templates, screen)
    }.stateIn(
        scope = viewModelScope,
        // Long enough to survive a rotation without re-reading four queries, short enough
        // that a backgrounded screen is not holding database observers open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SessionsUiState(),
    )

    fun onEvent(event: SessionsEvent) {
        when (event) {
            is SessionsEvent.TabSelected -> screenState.update { it.copy(tab = event.tab) }
            is SessionsEvent.QueryChanged -> screenState.update { it.copy(query = event.query) }
            is SessionsEvent.DateFilterSelected ->
                screenState.update { it.copy(dateFilter = event.filter) }
            SessionsEvent.SearchAndFiltersCleared ->
                screenState.update { it.copy(query = "", dateFilter = DateFilter.All) }
            is SessionsEvent.CalendarDaySelected -> selectDay(event.date)
            is SessionsEvent.CalendarMonthChanged -> changeMonth(event.forward)
        }
    }

    /**
     * Narrow the history to one day and show it.
     *
     * Both halves matter: a filter applied to a tab the user cannot see reads as a tap that
     * did nothing, and then surprises them the next time they open the history.
     */
    private fun selectDay(date: LocalDate) = screenState.update {
        it.copy(
            tab = SessionsTab.HISTORY,
            dateFilter = DateFilter.Between(start = date, end = date),
        )
    }

    /**
     * Page the calendar one month either way.
     *
     * The offset is stored rather than an absolute month, so a user who opened the tab
     * yesterday evening and comes back to it after midnight is still looking at "this month"
     * rather than at whichever month it was when the ViewModel was constructed.
     */
    private fun changeMonth(forward: Boolean) = screenState.update {
        it.copy(monthOffset = it.monthOffset + if (forward) 1 else -1)
    }

    /**
     * Assemble the whole screen.
     *
     * Ordered so each step narrows the one before it: what counts as history at all, then the
     * search and the date window over that, then the grouping. The calendar deliberately
     * re-reads the *unfiltered* set — see [CalendarPage].
     */
    private fun buildState(
        history: History,
        templates: List<TemplateSummary>?,
        screen: ScreenState,
    ): SessionsUiState {
        val now = clock.nowMillis()
        val today = localDateOf(now)
        val logged = history.sessions.orEmpty()
            .filterNot(WorkoutSession::isInProgress)
            .sortedByDescending(WorkoutSession::createdAt)

        val rows = logged.map { session ->
            session.toRow(
                totals = history.totals[session.id],
                records = history.recordInstants,
                today = today,
            )
        }
        val window = screen.dateFilter.resolve(today)
        val matches = rows.filter { row ->
            row.matches(screen.query.trim()) && row.startedOn in window
        }

        return SessionsUiState(
            tab = screen.tab,
            query = screen.query,
            dateFilter = screen.dateFilter,
            months = matches.groupIntoMonths(),
            sessionCount = rows.size,
            matchCount = matches.size,
            templates = templates.orEmpty(),
            calendar = rows.calendarPage(
                month = YearMonth.from(today).plusMonths(screen.monthOffset),
                today = today,
            ),
            isLoading = history.sessions == null,
            isLoadingTemplates = templates == null,
        )
    }

    /** What the screen shows and what the stores do not know about. */
    private data class ScreenState(
        val tab: SessionsTab = SessionsTab.HISTORY,
        val query: String = "",
        val dateFilter: DateFilter = DateFilter.All,
        /** Months from the current one. Zero is this month; negative is the past. */
        val monthOffset: Long = 0,
    )

    /**
     * The three observed reads, as one value.
     *
     * @param sessions null until the read comes back — see the field that builds this.
     */
    private data class History(
        val sessions: List<WorkoutSession>? = null,
        val totals: Map<Long, SessionTotals> = emptyMap(),
        val records: Map<Long, PersonalRecord> = emptyMap(),
    ) {

        /**
         * When every readable record was achieved, sorted, so a session can ask whether one
         * falls in its window with a binary search rather than a scan.
         *
         * Computed once per emission of the reads rather than per session: the alternative is
         * a scan of every record for every row, which on a full catalogue and a long history
         * is the kind of arithmetic that happens on the main dispatcher and is noticed.
         */
        val recordInstants: LongArray =
            records.values.map(PersonalRecord::achievedAt).sorted().toLongArray()
    }

    private companion object {
        /** The other screens' number, for the same reasons. */
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}

/**
 * One session as a row.
 *
 * The duration comes off the session's own columns — it began at `createdAt` and ended at
 * `completedAt` — which is why [SessionTotals] carries no duration to disagree with. The
 * fallback for a finished session somehow carrying no `completedAt` is `lastActiveAt`, the
 * last moment it is known to have been running, matching the recap this row opens.
 *
 * @param totals null for a session with no completed sets, which reads as zeroes: an
 *   abandoned session is a real row, and hiding it would leave a user looking for a workout
 *   they know they started.
 */
private fun WorkoutSession.toRow(
    totals: SessionTotals?,
    records: LongArray,
    today: LocalDate,
): HistoryRow {
    val finishedAt = completedAt ?: lastActiveAt
    val startedOn = localDateOf(createdAt)

    return HistoryRow(
        startedOn = startedOn,
        // The note is matched against but never drawn, so it is carried alongside the row
        // rather than inside it.
        notes = notes,
        session = HistorySession(
            id = id,
            name = name,
            startedAt = createdAt,
            duration = (finishedAt - createdAt).coerceAtLeast(0).milliseconds,
            totalSets = totals?.totalSets ?: 0,
            totalVolume = totals?.totalVolume ?: 0,
            hasRecord = records.hasValueIn(createdAt, finishedAt),
            isToday = startedOn == today,
        ),
    )
}

/**
 * A row, plus the two things the filters need and the row does not draw.
 *
 * The date is kept as a [LocalDate] rather than recomputed from the epoch millis each time a
 * filter runs: the conversion needs a time zone lookup, and doing it twice per session per
 * keystroke is the kind of cost that only shows up on somebody's four-hundredth workout.
 */
private data class HistoryRow(
    val startedOn: LocalDate,
    val notes: String?,
    val session: HistorySession,
)

/**
 * Whether this row survives the search box.
 *
 * Name **and** note, because those are the two places a lifter writes down what a session
 * was — "felt heavy, dropped the last set" is as good a handle on a workout as the name
 * above it, and a search that only read names would find nothing for it.
 *
 * A blank query matches everything rather than nothing, which is what makes the search and
 * the date window compose as an intersection without either having to know about the other.
 */
private fun HistoryRow.matches(query: String): Boolean {
    if (query.isEmpty()) return true
    return session.name?.contains(query, ignoreCase = true) == true ||
        notes?.contains(query, ignoreCase = true) == true
}

/** The window this filter means, resolved against [today]. */
private fun DateFilter.resolve(today: LocalDate): ClosedRange<LocalDate> = when (this) {
    DateFilter.All -> LocalDate.MIN..LocalDate.MAX
    // `days - 1` because the window includes today: "last 7 days" is today and the six
    // before it, not today and the seven before it.
    is DateFilter.LastDays -> today.minusDays(days - 1L)..today
    // Normalised rather than trusted: a range picker can hand back its ends either way
    // round, and a backwards range silently matches nothing.
    is DateFilter.Between -> minOf(start, end)..maxOf(start, end)
}

/**
 * The rows grouped under the month each fell in, newest month first.
 *
 * `groupBy` keeps insertion order, and the rows arrive newest first, so both the months and
 * the sessions inside them come out in the order the list draws them with no second sort.
 */
private fun List<HistoryRow>.groupIntoMonths(): List<SessionMonth> =
    groupBy { YearMonth.from(it.startedOn) }
        .map { (month, rows) -> SessionMonth(month = month, sessions = rows.map(HistoryRow::session)) }

/**
 * The heatmap for one month, built from every logged session rather than from the filtered
 * ones — see [CalendarPage].
 */
private fun List<HistoryRow>.calendarPage(month: YearMonth, today: LocalDate): CalendarPage {
    val inMonth = filter { YearMonth.from(it.startedOn) == month }
    val byDay = inMonth.groupBy { it.startedOn.dayOfMonth }
    val volumeByDay = byDay.mapValues { (_, rows) -> rows.sumOf { it.session.totalVolume } }
    val heaviest = volumeByDay.values.maxOrNull() ?: 0

    return CalendarPage(
        month = month,
        days = byDay.mapValues { (day, rows) ->
            TrainingDay(
                sessionCount = rows.size,
                load = loadOf(volume = volumeByDay.getValue(day), heaviest = heaviest),
            )
        },
        sessionCount = inMonth.size,
        totalVolume = volumeByDay.values.sum(),
        totalDuration = inMonth.fold(Duration.ZERO) { total, row -> total + row.session.duration },
        today = today.takeIf { YearMonth.from(it) == month },
    )
}

/**
 * Where a day falls on the month's two-step ramp.
 *
 * At or above half of the month's heaviest day is heavy. A month whose every session was
 * cardio has a heaviest of zero and comes out entirely light, rather than entirely heavy —
 * which is what `0 >= 0` would give, and would paint a rest-heavy month of running as the
 * hardest one the user has had.
 */
private fun loadOf(volume: Long, heaviest: Long): TrainingLoad =
    if (heaviest > 0 && volume * 2 >= heaviest) TrainingLoad.HEAVY else TrainingLoad.LIGHT

/**
 * Whether this sorted array holds a value inside `[start, end]`.
 *
 * A binary search for the first element not below [start], then one comparison. The scan it
 * replaces is O(records) per session, and both grow: the records with the catalogue, the
 * sessions with every workout the user logs.
 */
private fun LongArray.hasValueIn(start: Long, end: Long): Boolean {
    if (isEmpty()) return false

    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] < start) low = middle + 1 else high = middle
    }

    return low < size && this[low] <= end
}
