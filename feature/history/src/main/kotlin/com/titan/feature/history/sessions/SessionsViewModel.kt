package com.titan.feature.history.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.SessionTotals
import com.titan.core.domain.model.TemplateError
import com.titan.core.domain.model.TemplateSummary
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.CreateTemplateUseCase
import com.titan.core.domain.usecase.DeleteTemplateUseCase
import com.titan.core.domain.usecase.GetTemplateSummariesUseCase
import com.titan.core.domain.usecase.UpdateTemplateUseCase
import com.titan.core.ui.component.TrainingDay
import com.titan.core.ui.component.TrainingLoad
import com.titan.core.ui.format.localDateOf
import com.titan.core.ui.state.savedScreenState
import com.titan.feature.history.template.toDrafts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
/*
 * Eight injected dependencies, which is one over the threshold and is what this screen is: four
 * reads for the three tabs, and the three template use cases the tab's row actions call. Bundling
 * any of them would be a type invented to satisfy a count.
 */
@Suppress("LongParameterList")
class SessionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    sessionRepository: SessionRepository,
    personalRecordRepository: PersonalRecordRepository,
    getTemplateSummaries: GetTemplateSummariesUseCase,
    private val templateRepository: TemplateRepository,
    private val createTemplate: CreateTemplateUseCase,
    private val updateTemplate: UpdateTemplateUseCase,
    private val deleteTemplate: DeleteTemplateUseCase,
    private val clock: Clock,
) : ViewModel() {

    /**
     * What the screen owns, as opposed to what the stores do.
     *
     * Saved, so the tab, the search, the date filter and the month the calendar is scrolled to
     * come back after the process is killed. None of it is derivable from the reads underneath:
     * a user who narrowed four hundred sessions to a week in March and then answered a phone
     * call would otherwise return to an unfiltered list on the wrong tab.
     */
    private val screenState = savedStateHandle.savedScreenState(viewModelScope) { ScreenState() }

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
        // Built from the restored screen state rather than from nothing: `combine` holds its
        // first emission until all four reads have answered, and an empty initial value would
        // draw the wrong tab and an unfiltered header for that whole window before putting the
        // user's own choices back.
        initialValue = buildState(history = History(), templates = null, screen = screenState.value),
    )

    fun onEvent(event: SessionsEvent) {
        if (onTemplateEvent(event)) return

        when (event) {
            is SessionsEvent.TabSelected -> screenState.update { it.copy(tab = event.tab) }

            is SessionsEvent.QueryChanged -> screenState.update { it.copy(query = event.query) }

            is SessionsEvent.DateFilterSelected ->
                screenState.update { it.copy(dateFilter = event.filter) }

            SessionsEvent.SearchAndFiltersCleared ->
                screenState.update { it.copy(query = "", dateFilter = DateFilter.All) }

            is SessionsEvent.CalendarDaySelected -> selectDay(event.date)

            is SessionsEvent.CalendarMonthChanged -> changeMonth(event.forward)

            else -> Unit
        }
    }

    /**
     * The nine events that only concern the templates tab's row actions, handled together.
     *
     * Split off [onEvent] so neither `when` branches over two unrelated subjects: those are about
     * reading the history, and these are about changing a saved plan.
     *
     * @return whether [event] was one of them.
     */
    private fun onTemplateEvent(event: SessionsEvent): Boolean {
        when (event) {
            is SessionsEvent.TemplateRenameRequested -> openNaming(
                templateId = event.templateId,
                kind = TemplateNaming.RENAME,
                // The template's own name, so the commonest rename — a typo, a word added — is
                // an edit rather than a retype. Its category comes with it: the dialog shows
                // both, so a rename that left the chips blank would clear one by omission.
                name = { summary -> summary.template.name },
            )

            is SessionsEvent.TemplateDuplicateRequested -> openNaming(
                templateId = event.templateId,
                kind = TemplateNaming.DUPLICATE,
                name = { event.proposedName },
            )

            // Typing clears the refusal: it was about the name that *was* in the field, and an
            // error left under a name nobody has claimed reads as one that cannot be fixed.
            is SessionsEvent.TemplateNameChanged -> editNaming {
                it.copy(name = event.name, nameTaken = false)
            }

            is SessionsEvent.TemplateCategoryChanged -> editNaming {
                it.copy(category = event.category)
            }

            SessionsEvent.TemplateNameConfirmed -> confirmNaming()

            is SessionsEvent.TemplateDeleteRequested -> {
                // Ignored for an id the list no longer holds, like [openNaming]: the row is about
                // to disappear anyway, and a dialog with nothing behind it is worse than a tap
                // that did nothing.
                val summary = templateSummary(event.templateId) ?: return true
                screenState.update {
                    it.copy(
                        templateAction = TemplateAction.Deleting(
                            templateId = event.templateId,
                            name = summary.template.name,
                        ),
                    )
                }
            }

            SessionsEvent.TemplateDeleteConfirmed -> confirmDelete()

            // Ignored mid-write, which is the dialogs' own rule: they go inert rather than
            // closing, so a refusal has somewhere to be reported, and clearing the action on the
            // way out would let a second confirm race the first.
            SessionsEvent.TemplateActionDismissed ->
                if (!screenState.value.templateAction.isBusy()) {
                    screenState.update { it.copy(templateAction = null) }
                }

            SessionsEvent.MessageShown -> screenState.update { it.copy(message = null) }

            else -> return false
        }
        return true
    }

    /**
     * Raise the name dialog on a template that is still in the list.
     *
     * Ignored for an id the list no longer holds, rather than opening a dialog over a template
     * that has gone: the row is about to disappear anyway, and a dialog with nothing behind it
     * is worse than a tap that did nothing.
     */
    private fun openNaming(
        templateId: Long,
        kind: TemplateNaming,
        name: (TemplateSummary) -> String,
    ) {
        val summary = templateSummary(templateId) ?: return
        screenState.update {
            it.copy(
                templateAction = TemplateAction.Naming(
                    templateId = templateId,
                    kind = kind,
                    name = name(summary),
                    category = summary.template.category,
                ),
            )
        }
    }

    /**
     * Change what the name dialog is holding, if that is what is up.
     *
     * Unguarded, because the write path itself uses it: [editNaming] is the one the user's own
     * edits go through, and it is the one that has to stop while a write is out.
     */
    private fun updateNaming(change: (TemplateAction.Naming) -> TemplateAction.Naming) {
        screenState.update { state ->
            val naming = state.templateAction as? TemplateAction.Naming ?: return@update state
            state.copy(templateAction = change(naming))
        }
    }

    /**
     * The user's own edit to the dialog, ignored while a write is in flight.
     *
     * The dialog draws itself inert over a write, so this is belt and braces — but a keystroke
     * already in flight when the confirm landed would otherwise change the name *under* a write
     * that was made with the old one, and the field would then disagree with what was stored.
     */
    private fun editNaming(change: (TemplateAction.Naming) -> TemplateAction.Naming) {
        if ((screenState.value.templateAction as? TemplateAction.Naming)?.isSaving == true) return
        updateNaming(change)
    }

    /**
     * Write the rename or the copy, leaving the dialog up and inert while the write is in flight.
     *
     * Both go through the same body read, because both are writes of a *whole* template: the use
     * cases take a name and an exercise list together — see [TemplateRepository.update] — so
     * even a rename has to hand back the exercises it is not changing.
     *
     * A rename therefore replaces the template's exercise rows, which `SET NULL`s
     * `session_exercise.template_exercise_id` for sessions that were started from them. The
     * sessions themselves are untouched; what is lost is "this set came from that template line"
     * for past workouts, which nothing reads yet. It is the cost of the store's one write, not a
     * decision taken here.
     */
    private fun confirmNaming() {
        val naming = screenState.value.templateAction as? TemplateAction.Naming ?: return
        if (naming.isSaving) return
        updateNaming { it.copy(isSaving = true) }

        // Neither write is a patch — see `UpdateTemplateUseCase` — so the icon the dialog does
        // not ask about has to be handed back with everything else, or a rename clears it.
        val icon = templateSummary(naming.templateId)?.template?.icon

        viewModelScope.launch {
            val outcome = runTemplateWrite {
                val exercises = templateRepository.getExercises(naming.templateId).first().toDrafts()
                when (naming.kind) {
                    TemplateNaming.RENAME -> updateTemplate(
                        templateId = naming.templateId,
                        name = naming.name,
                        exercises = exercises,
                        category = naming.category,
                        icon = icon,
                    )

                    TemplateNaming.DUPLICATE -> createTemplate(
                        name = naming.name,
                        exercises = exercises,
                        category = naming.category,
                        icon = icon,
                    )
                }.map { }
            } ?: return@launch

            outcome
                .onSuccess {
                    screenState.update {
                        it.copy(
                            templateAction = null,
                            // Nothing for a rename: the row redrawing under its new name is the
                            // confirmation, and the copy lands at its own sort position rather
                            // than under the row it came from.
                            message = when (naming.kind) {
                                TemplateNaming.RENAME -> it.message
                                TemplateNaming.DUPLICATE -> SessionsMessage.TEMPLATE_DUPLICATED
                            },
                        )
                    }
                }
                .onFailure { failure -> reportNamingFailure(failure) }
        }
    }

    /**
     * How a refused name or copy is drawn.
     *
     * A collision keeps the dialog, with the refusal under the field the user can fix. Everything
     * else closes it: a template that has gone is not something a second press resolves, and
     * neither is a body the use case would not accept.
     */
    private fun reportNamingFailure(failure: Throwable) {
        if (failure is TemplateError.DuplicateName) {
            updateNaming { it.copy(isSaving = false, nameTaken = true) }
            return
        }
        screenState.update {
            it.copy(
                templateAction = null,
                message = if (failure is TemplateError.NotFound) {
                    SessionsMessage.TEMPLATE_GONE
                } else {
                    SessionsMessage.TEMPLATE_ACTION_FAILED
                },
            )
        }
    }

    /**
     * Delete the template, leaving the sessions started from it exactly where they are.
     *
     * `workout_session.template_id` is `SET NULL`, so the history survives the plan it was run
     * from — see `DeleteTemplateUseCase`. That is the whole reason this needs no warning about
     * losing workouts: there are none to lose.
     */
    private fun confirmDelete() {
        val deleting = screenState.value.templateAction as? TemplateAction.Deleting ?: return
        if (deleting.isDeleting) return
        screenState.update { it.copy(templateAction = deleting.copy(isDeleting = true)) }

        viewModelScope.launch {
            val outcome = runTemplateWrite { deleteTemplate(deleting.templateId) }
                ?: return@launch

            screenState.update {
                it.copy(
                    templateAction = null,
                    message = when {
                        outcome.isSuccess -> SessionsMessage.TEMPLATE_DELETED

                        outcome.exceptionOrNull() is TemplateError.NotFound ->
                            SessionsMessage.TEMPLATE_GONE

                        else -> SessionsMessage.TEMPLATE_ACTION_FAILED
                    },
                )
            }
        }
    }

    /**
     * Run a template write, catching the failure the use cases do not return.
     *
     * Two failure channels, because there are two kinds of failure. A use case *returns* its
     * refusals — the name, the body, an id that is not there — and the store *throws* when it
     * cannot be written at all. Catching only the first would leave the second to kill the
     * coroutine with a dialog still up and nothing said under it.
     *
     * @return the use case's own result, or **null** when the store threw — in which case the
     *   dialog has already been closed and the failure reported, and there is nothing left for
     *   the caller to decide.
     */
    private suspend fun runTemplateWrite(write: suspend () -> Result<Unit>): Result<Unit>? =
        try {
            write()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            screenState.update {
                it.copy(
                    templateAction = null,
                    message = SessionsMessage.TEMPLATE_ACTION_FAILED,
                )
            }
            null
        }

    /**
     * The row the action is about, or null once the list no longer holds it.
     *
     * Off the template read itself rather than off [uiState], which is a `stateIn` downstream of
     * it: a value written to a source flow does not reach a combined one until it has been
     * dispatched, so a guard asked of [uiState] in the same frame as the update it is guarding
     * against reads the state as it was before. Every question this ViewModel asks of itself is
     * asked of what it holds — the same rule `TemplateEditorViewModel.save` follows.
     */
    private fun templateSummary(templateId: Long): TemplateSummary? =
        templates.value?.firstOrNull { it.template.id == templateId }

    /**
     * Narrow the history to one day and show it.
     *
     * Both halves matter: a filter applied to a tab the user cannot see reads as a tap that
     * did nothing, and then surprises them the next time they open the history.
     */
    private fun selectDay(date: LocalDate) =
        screenState.update {
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
    private fun changeMonth(forward: Boolean) =
        screenState.update {
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
            templateAction = screen.templateAction,
            message = screen.message,
            calendar = rows.calendarPage(
                month = YearMonth.from(today).plusMonths(screen.monthOffset),
                today = today,
            ),
            isLoading = history.sessions == null,
            isLoadingTemplates = templates == null,
        )
    }

    /**
     * What the screen shows and what the stores do not know about.
     *
     * `@Serializable` so it survives the process being killed with the tab open — see
     * [savedScreenState]. The sessions, totals and records the list is drawn from are not here
     * and deliberately not saved: they are four queries that re-run in milliseconds, and a
     * history of a thousand sessions stuffed into saved state is the `TransactionTooLargeException`
     * this screen would otherwise be the first to hit.
     */
    @Serializable
    private data class ScreenState(
        val tab: SessionsTab = SessionsTab.HISTORY,
        val query: String = "",
        val dateFilter: DateFilter = DateFilter.All,
        /** Months from the current one. Zero is this month; negative is the past. */
        val monthOffset: Long = 0,
        val templateAction: TemplateAction? = null,
        /**
         * Not saved. A one-shot the screen shows and reports back; replayed after twenty minutes
         * it would announce a copy the user watched appear before they put the phone down.
         */
        @Transient
        val message: SessionsMessage? = null,
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
            timedSeconds = totals?.timedSeconds ?: 0,
            totalDistance = totals?.totalDistance ?: 0,
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
private fun DateFilter.resolve(today: LocalDate): ClosedRange<LocalDate> =
    when (this) {
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
private fun List<HistoryRow>.calendarPage(
    month: YearMonth,
    today: LocalDate,
): CalendarPage {
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
private fun loadOf(
    volume: Long,
    heaviest: Long,
): TrainingLoad = if (heaviest > 0 && volume * 2 >= heaviest) TrainingLoad.HEAVY else TrainingLoad.LIGHT

/**
 * Whether this sorted array holds a value inside `[start, end]`.
 *
 * A binary search for the first element not below [start], then one comparison. The scan it
 * replaces is O(records) per session, and both grow: the records with the catalogue, the
 * sessions with every workout the user logs.
 */
private fun LongArray.hasValueIn(
    start: Long,
    end: Long,
): Boolean {
    if (isEmpty()) return false

    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] < start) low = middle + 1 else high = middle
    }

    return low < size && this[low] <= end
}

/**
 * Whether a write is already out for this action.
 *
 * Both cases carry a flag of their own — they are written by different use cases and mean
 * different things — and every caller asking "can this be dismissed" wants them together.
 */
private fun TemplateAction?.isBusy(): Boolean =
    when (this) {
        is TemplateAction.Naming -> isSaving
        is TemplateAction.Deleting -> isDeleting
        null -> false
    }
