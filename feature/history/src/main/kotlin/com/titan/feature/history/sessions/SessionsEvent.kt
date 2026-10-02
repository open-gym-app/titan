package com.titan.feature.history.sessions

import com.titan.core.domain.model.TemplateCategory
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

    /** A template row's "Rename". Raises the name dialog on what it is called now. */
    data class TemplateRenameRequested(val templateId: Long) : SessionsEvent

    /**
     * A template row's "Duplicate". Raises the same dialog on a proposed name.
     *
     * The proposal comes *from the screen* rather than from the ViewModel, because "Push day
     * (copy)" is a translated string and a ViewModel does not reach for a `Context`. It is only
     * a proposal: the dialog is what makes it the user's, which is also how a copy whose name is
     * already taken is resolved — the refusal is drawn under the field, like every other one.
     */
    data class TemplateDuplicateRequested(
        val templateId: Long,
        val proposedName: String,
    ) : SessionsEvent

    /** The name field of whichever of the two is up changed. Also clears a name-taken refusal. */
    data class TemplateNameChanged(val name: String) : SessionsEvent

    /** A category chip was chosen in that dialog, or `null` for the `None` chip. */
    data class TemplateCategoryChanged(val category: TemplateCategory?) : SessionsEvent

    /** The name dialog was confirmed. This is the one that writes — a rename or a copy. */
    data object TemplateNameConfirmed : SessionsEvent

    /** A template row's "Delete". Raises the confirmation; writes nothing. */
    data class TemplateDeleteRequested(val templateId: Long) : SessionsEvent

    /** The delete was confirmed. Sessions started from the template are untouched. */
    data object TemplateDeleteConfirmed : SessionsEvent

    /** Whichever dialog is up was dismissed. Nothing was written. */
    data object TemplateActionDismissed : SessionsEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : SessionsEvent
}
