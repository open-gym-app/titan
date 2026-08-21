package com.opengym.feature.history.sessions.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.format.formatMonthAndYear
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.component.NewButton
import com.opengym.feature.history.sessions.DateFilter
import com.opengym.feature.history.sessions.SessionsTab
import com.opengym.feature.history.sessions.SessionsUiState

/**
 * The tab's own header: what is in here, and the three things to do about it.
 *
 * The shell has already drawn a bar saying "Sessions" with a settings gear in it, so this
 * adds only what the shell cannot know — the same division `LibraryHeader` makes, and the
 * same one row rather than the 130dp that pinning a search box and a filter row would cost
 * under one bar and over another.
 *
 * ### The count says three different things
 *
 * Because the number means three different things. On the history tab it is how much has been
 * logged, or how much survived the filters — which is the only thing on screen saying the list
 * is narrower than it looks. On templates it is how many there are. On the calendar it is the
 * month, which is what that tab is *about*; a session count for a filter the calendar does not
 * obey would be a number answering another tab's question.
 *
 * ### Search and dates belong to the history and are drawn only there
 *
 * A search box over a month grid narrows nothing, and a date window over a list of templates
 * is a control with no referent. Both are absent rather than disabled on the other two tabs:
 * a control that is permanently greyed is one the user keeps re-discovering.
 */
@Composable
internal fun SessionsHeader(
    state: SessionsUiState,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    onOpenDates: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // The same inset both sides. The end used to be `sm`, which is what an icon
            // button wants — its glyph carries 12dp of its own padding to the edge of the
            // touch target. The `+ New` at the end of the row is a filled shape with no
            // such inset, and at 6dp it sits all but flush against the screen.
            .padding(horizontal = OpenGymTheme.spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Eyebrow(text = state.subtitle(), modifier = Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.tab == SessionsTab.HISTORY) {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.sessions_search),
                        tint = if (searchExpanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                DateFilterButton(filter = state.dateFilter, onClick = onOpenDates)
            }
            NewButton(
                label = stringResource(R.string.sessions_new),
                description = stringResource(R.string.sessions_new_description),
                onClick = onNewSession,
            )
        }
    }
}

/**
 * The way into the date sheet, tinted and filled when a window is set.
 *
 * Filled rather than only tinted, for the reason the library's funnel is: a hue difference on
 * a 24dp glyph is not a state anybody reads at a glance in bad light. And it has to be
 * readable — a date window set and forgotten is a history that has silently lost a year, and
 * this button is the only thing on screen that says so.
 */
@Composable
private fun DateFilterButton(
    filter: DateFilter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtering = filter != DateFilter.All

    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (filtering) Icons.Filled.DateRange else Icons.Outlined.DateRange,
            contentDescription = stringResource(R.string.sessions_dates),
            tint = if (filtering) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * `24 SESSIONS`, `6 OF 24`, `6 TEMPLATES`, `APRIL 2026 · 13 SESSIONS` — and nothing at all
 * until the read that answers it has landed.
 *
 * A count is zero before the query comes back as surely as it is for a user who has logged
 * nothing, and the header cannot tell the two apart from the number alone. Drawing it anyway
 * tells somebody with four hundred sessions that they have none, for as long as the read takes
 * — which is the same lie [com.opengym.feature.history.sessions.component.HistoryList] goes out
 * of its way not to tell by drawing nothing while `isLoading`. Blank rather than a placeholder:
 * the row is one line of eyebrow text that fills in a frame later, and a shimmer on it would be
 * more motion than the fact is worth.
 *
 * The calendar keeps its month either way and loses only the count beside it: the month is
 * what that tab is *about* and is known before any read resolves, so a heading that vanished
 * would take the one certain fact with it.
 */
@Composable
private fun SessionsUiState.subtitle(): String = when (tab) {
    SessionsTab.HISTORY -> when {
        isLoading -> ""
        hasFilters -> stringResource(R.string.sessions_count_filtered, matchCount, sessionCount)
        else -> pluralStringResource(R.plurals.sessions_count, sessionCount, sessionCount)
    }

    SessionsTab.TEMPLATES -> if (isLoadingTemplates) {
        ""
    } else {
        pluralStringResource(R.plurals.sessions_templates_count, templates.size, templates.size)
    }

    SessionsTab.CALENDAR -> if (isLoading) {
        formatMonthAndYear(calendar.month)
    } else {
        stringResource(
            R.string.sessions_calendar_heading,
            formatMonthAndYear(calendar.month),
            pluralStringResource(
                R.plurals.sessions_count,
                calendar.sessionCount,
                calendar.sessionCount,
            ),
        )
    }
}

/** The three tab labels, in the order [SessionsTab] declares them. */
@Composable
internal fun sessionsTabLabels(): List<String> = listOf(
    stringResource(R.string.sessions_tab_history),
    stringResource(R.string.sessions_tab_templates),
    stringResource(R.string.sessions_tab_calendar),
)
