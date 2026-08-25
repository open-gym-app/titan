package com.opengym.feature.history.sessions

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.ui.component.TrainingDay
import java.time.LocalDate
import java.time.YearMonth
import kotlin.time.Duration

/**
 * Everything the sessions screen draws, as one value.
 *
 * The three tabs share this rather than holding one state each, because they are not three
 * screens: the calendar's tap sets the history's filter, the header's count is the history's
 * count, and a tab switch must not re-read anything. What differs between them is which part
 * of this is rendered.
 *
 * Everything arrives already filtered, grouped and counted — see [SessionsViewModel]. The
 * screen renders [months] in order and decides nothing, which is what lets a Compose test
 * hand it four sessions in two months and assert on what is drawn.
 *
 * @param tab which of the three is showing.
 * @param query what is in the search box. Matched against a session's name *and* its note.
 * @param dateFilter which window of time the history is narrowed to. Combines with [query]
 *   as an intersection.
 * @param months the history, newest first, grouped by the month each session started in.
 *   Empty when nothing matches — see [hasFilters] for which of the two empty states that is.
 * @param sessionCount how many sessions exist, filters or no filters. The header's count.
 * @param matchCount how many are in [months]. Equal to [sessionCount] when nothing narrows.
 * @param templates the saved templates, as their own tab lists them.
 * @param calendar the month the calendar tab is showing, and what was trained in it.
 * @param isLoading there is no answer for the history yet — the read has not landed. Distinct
 *   from "no sessions" and from "nothing matched", both of which would otherwise be drawn
 *   over a tab that is merely still opening.
 * @param isLoadingTemplates the same, for the templates tab. Separate because the two reads
 *   are separate queries and either can answer first; one flag would draw an empty template
 *   list under a loaded history.
 * @param templateAction what a template row's overflow menu has opened, or **null** for none. One
 *   field rather than one per dialog: only one of them can be up, and two nullable drafts is two
 *   ways to describe a state that cannot happen.
 * @param message a one-shot outcome to show and forget.
 */
@Immutable
data class SessionsUiState(
    val tab: SessionsTab = SessionsTab.HISTORY,
    val query: String = "",
    val dateFilter: DateFilter = DateFilter.All,
    val months: List<SessionMonth> = emptyList(),
    val sessionCount: Int = 0,
    val matchCount: Int = 0,
    val templates: List<TemplateSummary> = emptyList(),
    val calendar: CalendarPage = CalendarPage(),
    val isLoading: Boolean = true,
    val isLoadingTemplates: Boolean = true,
    val templateAction: TemplateAction? = null,
    val message: SessionsMessage? = null,
) {

    /** Whether anything is narrowing the history — which decides *which* empty state it draws. */
    val hasFilters: Boolean
        get() = query.isNotBlank() || dateFilter != DateFilter.All
}

/**
 * What a template row's overflow menu opened, and what it is holding.
 *
 * The three row actions are two dialogs: rename and duplicate ask the same question — what to
 * call it — and delete asks whether to. So the naming half is one case with a [TemplateNaming]
 * on it rather than two cases that would have to be kept in step, which is also what lets the
 * screen raise one `TemplateNameDialog` for both.
 *
 * Nothing here is written until it is confirmed. Backing out of any of them costs nothing,
 * which is why none of the three needs an undo.
 */
sealed interface TemplateAction {

    /** The template being acted on. Held so a confirm does not have to be told again. */
    val templateId: Long

    /**
     * Naming a template — either the one being renamed, or the copy about to be made.
     *
     * @param name what is in the field. Seeded from the template's own name for a rename, and
     *   from "<name> (copy)" for a duplicate, so the commonest answer is already typed.
     * @param nameTaken the last attempt collided with another template's name. Drawn under the
     *   field, because that is the thing the user has to change and the dialog is still up with
     *   what they wrote in it.
     */
    @Immutable
    data class Naming(
        override val templateId: Long,
        val kind: TemplateNaming,
        val name: String,
        val category: TemplateCategory? = null,
        val isSaving: Boolean = false,
        val nameTaken: Boolean = false,
    ) : TemplateAction

    /**
     * Deleting a template, pending the confirmation.
     *
     * @param name what is being deleted, for the question to say. "Delete this template?" is a
     *   different question when the answer is a plan the user built up over a month.
     */
    @Immutable
    data class Deleting(
        override val templateId: Long,
        val name: String,
        val isDeleting: Boolean = false,
    ) : TemplateAction
}

/** Which of the two things a [TemplateAction.Naming] is about to do. */
enum class TemplateNaming {

    /** Retitle the template in place, keeping its exercises and everything started from it. */
    RENAME,

    /** Write a second template with the same body, under a name of its own. */
    DUPLICATE,
}

/**
 * Something that happened on this screen and needs saying once.
 *
 * An enum rather than a string, matching every other screen here: the ViewModel does not reach
 * for a `Context`, and every user-visible word stays in the screen's resources.
 *
 * There is no message for a rename. The row redrawing under its new name is the confirmation,
 * and a snackbar over a list the user is looking at is the app reading its own writing back.
 */
enum class SessionsMessage {

    /** A copy was written. Said, because it lands at the list's sort position and not under the
     *  row it was copied from, so "did that work" is a real question. */
    TEMPLATE_DUPLICATED,

    /** A template was deleted. Said, because a row disappearing is also what a bug looks like. */
    TEMPLATE_DELETED,

    /**
     * The template is not there any more.
     *
     * What a row acted on from a list that has gone stale looks like — the everyday cause is the
     * same template deleted from its editor on another screen. Reported rather than swallowed:
     * the user asked something of a thing that no longer exists, and the list is about to lose
     * the row they were aiming at.
     */
    TEMPLATE_GONE,

    /** The write could not be made at all. Nothing changed. */
    TEMPLATE_ACTION_FAILED,
}

/** The three surfaces the Sessions tab holds. Ordered as the tab strip draws them. */
enum class SessionsTab {
    HISTORY,
    TEMPLATES,
    CALENDAR,
}

/**
 * The window of time the history is narrowed to.
 *
 * A sealed type rather than a nullable pair of dates, because the *shape* of the filter is
 * what the sheet and the chip both render: "Last 7 days" has to keep saying that as the days
 * roll over, which a pair resolved once could not. The dates are computed against today
 * whenever the state is built — see [SessionsViewModel].
 */
sealed interface DateFilter {

    /** No window. Every session the user has logged. */
    data object All : DateFilter

    /**
     * The last [days] days, today included.
     *
     * `LastDays(7)` is today and the six before it, which is what a lifter means by "this
     * week" and what makes the two ends of a rolling window move together.
     */
    data class LastDays(val days: Int) : DateFilter

    /**
     * An explicit window, both ends included.
     *
     * Also what a tapped calendar day becomes — `Between(d, d)` — rather than a fourth case
     * for one day, since a single day *is* a window whose ends match and the sheet renders it
     * as one either way.
     */
    data class Between(
        val start: LocalDate,
        val end: LocalDate,
    ) : DateFilter
}

/**
 * One month of history, and the sessions in it.
 *
 * The grouping is here rather than in the screen because it decides what is drawn — a heading
 * per month, and a day rail on each row that only reads as a date under one. See
 * [com.opengym.core.ui.component.SessionRow].
 */
@Immutable
data class SessionMonth(
    val month: YearMonth,
    val sessions: List<HistorySession>,
)

/**
 * One row of the history list.
 *
 * Assembled once, in the ViewModel, rather than looked up per row at draw time: the totals
 * and the records arrive as maps over every session, and a row that reached into them would
 * be two lookups inside a `LazyColumn` item, repeated on every scroll pass.
 *
 * @param duration wall-clock time the session ran, paused stretches included. Derived from
 *   the session's own row rather than from its sets — see
 *   [com.opengym.core.domain.model.SessionTotals].
 * @param totalSets completed sets. Zero for a session with none, which is what a row for an
 *   abandoned session shows.
 * @param hasRecord whether a personal record was set here. See [SessionsViewModel] for how
 *   that is decided, and what it cannot yet see.
 * @param isToday whether the session started today, which draws its date rail in the accent.
 */
@Immutable
data class HistorySession(
    val id: Long,
    val name: String?,
    val startedAt: Long,
    val duration: Duration,
    val totalSets: Int,
    val totalVolume: Long,
    val hasRecord: Boolean = false,
    val isToday: Boolean = false,
)

/**
 * The calendar tab's page: one month, what was trained in it, and what it added up to.
 *
 * The figures under the grid are the *month's*, not the filtered history's. The calendar is
 * a way of reading a month rather than a view of the current search, and a heatmap that
 * dimmed as somebody typed in the history's search box would be answering a question nobody
 * asked of it.
 *
 * @param month the page being shown. Defaults to the current month so a state built by hand
 *   is not stuck in 1970; every preview and test passes one explicitly, because a screenshot
 *   whose contents depend on the day it was recorded is not a reference.
 * @param days what was trained, keyed by day of month, for
 *   [com.opengym.core.ui.component.TrainingCalendar].
 * @param today the day to ring, or **null** when the month on screen is not the one today is
 *   in. Null is the ordinary case as soon as the user pages back.
 */
@Immutable
data class CalendarPage(
    val month: YearMonth = YearMonth.now(),
    val days: Map<Int, TrainingDay> = emptyMap(),
    val sessionCount: Int = 0,
    val totalVolume: Long = 0,
    val totalDuration: Duration = Duration.ZERO,
    val today: LocalDate? = null,
)
