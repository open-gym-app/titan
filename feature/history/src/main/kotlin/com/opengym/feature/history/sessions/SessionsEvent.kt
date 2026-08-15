package com.opengym.feature.history.sessions

import java.time.LocalDate

/**
 * Everything the sessions screen can report.
 *
 * Navigation is *not* here: opening a session, starting one and opening a template all leave
 * the screen, and a feature does not own the `NavController`. Those are parameters of the
 * screen rather than events — see `SessionsScreen`.
 */
sealed interface SessionsEvent {

    /** A tab was chosen. */
    data class TabSelected(val tab: SessionsTab) : SessionsEvent

    /** The search box changed. Fires per keystroke; the list follows. */
    data class QueryChanged(val query: String) : SessionsEvent

    /** A window of time was chosen in the date sheet, or cleared to [DateFilter.All]. */
    data class DateFilterSelected(val filter: DateFilter) : SessionsEvent

    /** The way out of a no-results state: drops the search and the date window together. */
    data object SearchAndFiltersCleared : SessionsEvent

    /**
     * A trained day was tapped on the calendar.
     *
     * Two things at once, deliberately: it narrows the history to that day *and* switches to
     * it. A filter applied to a tab the user cannot see would look like a tap that did
     * nothing, and then surprise them the next time they opened the history.
     */
    data class CalendarDaySelected(val date: LocalDate) : SessionsEvent

    /** The calendar's ‹ and › — one month back or forward. */
    data class CalendarMonthChanged(val forward: Boolean) : SessionsEvent
}
