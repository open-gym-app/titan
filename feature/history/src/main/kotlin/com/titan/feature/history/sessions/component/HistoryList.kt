package com.titan.feature.history.sessions.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.titan.core.ui.component.EmptyState
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.SessionRow
import com.titan.core.ui.format.formatMonthAndYear
import com.titan.core.ui.test.TitanTestTags
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.history.R
import com.titan.feature.history.sessions.SessionMonth
import com.titan.feature.history.sessions.SessionsUiState

/**
 * Everything the user has logged, newest first, under a heading per month.
 *
 * ### Why the rows are not in cards
 *
 * The wireframes draw each session as its own bordered box, and this deliberately does not:
 * a card per row on a list with no upper bound is a border and a surface per item to compose
 * and to overdraw, for a separation a hairline already makes. It is the same trade
 * `ExerciseLibraryScreen` documents for its 873 rows, and the design's own rule — separate
 * with a line, not with elevation — comes out the same either way.
 *
 * ### The month heading is half of every row's date
 *
 * A row's rail carries a day number and a weekday and no month, which only reads as a date
 * under a heading that supplies one. That is what keeps the rail two glyphs wide on a 360dp
 * screen where the name, three figures and a chevron also have to fit — see
 * [com.titan.core.ui.component.SessionRow].
 */
@Composable
internal fun HistoryList(
    state: SessionsUiState,
    listState: LazyListState,
    onSessionClick: (sessionId: Long) -> Unit,
    onStartSession: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The tag is what `:benchmark` flings; see `TitanTestTags`.
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(TitanTestTags.SESSIONS_HISTORY_LIST),
    ) {
        if (state.months.isEmpty()) {
            // Nothing is drawn while the first read is in flight: the query is one indexed
            // read of a local table, so a spinner would be a flash and an empty state under
            // it would be a lie.
            if (!state.isLoading) {
                item(key = EMPTY_KEY) {
                    HistoryEmptyState(
                        hasFilters = state.hasFilters,
                        onStartSession = onStartSession,
                        onClearFilters = onClearFilters,
                    )
                }
            }
            return@LazyColumn
        }

        state.months.forEach { month ->
            item(key = "month-${month.month}") {
                MonthHeading(month = month)
            }
            items(items = month.sessions, key = { it.id }) { session ->
                SessionRow(
                    name = session.name,
                    startedAt = session.startedAt,
                    duration = session.duration,
                    totalSets = session.totalSets,
                    totalVolume = session.totalVolume,
                    timedSeconds = session.timedSeconds,
                    totalDistance = session.totalDistance,
                    hasRecord = session.hasRecord,
                    isToday = session.isToday,
                    // Left to the compiler: under strong skipping a lambda over stable captures
                    // (`onSessionClick`, a `Long`) is memoised on *both*, so a new
                    // `onSessionClick` reaches rows already on screen. A hand-written
                    // `remember(session.id)` would drop it from the key.
                    onClick = { onSessionClick(session.id) },
                )
                HairlineDivider()
            }
        }

        // Air under the last row, so the final divider is not flush against the bottom of
        // the window.
        item(key = FOOTER_KEY) {
            Spacer(modifier = Modifier.height(TitanTheme.spacing.section))
        }
    }
}

/**
 * `APRIL 2026 · 4`, with the shared [SectionHeading]'s rule running out to the margin.
 *
 * One node rather than a month beside a count, for the reason the library's section header is:
 * a screen reader that read them as two would say "April 2026" and "dot four" three swipes
 * apart.
 */
@Composable
private fun MonthHeading(
    month: SessionMonth,
    modifier: Modifier = Modifier,
) {
    val heading = stringResource(
        R.string.sessions_month_heading,
        formatMonthAndYear(month.month),
        month.sessions.size,
    )

    SectionHeading(
        text = heading,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(
            start = TitanTheme.spacing.screen,
            end = TitanTheme.spacing.screen,
            top = TitanTheme.spacing.section,
            bottom = TitanTheme.spacing.md,
        ),
    )
}

/**
 * The two empty states, which are not the same state.
 *
 * A history that has been filtered to nothing has a way out and is offered it. One that is
 * empty because nothing has been logged is offered the thing that would fill it, which is the
 * whole point of the tab existing before there is anything in it.
 */
@Composable
private fun HistoryEmptyState(
    hasFilters: Boolean,
    onStartSession: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hasFilters) {
        EmptyState(
            glyph = stringResource(R.string.sessions_glyph_search),
            title = stringResource(R.string.sessions_no_results_title),
            description = stringResource(R.string.sessions_no_results_description),
            actionLabel = stringResource(R.string.sessions_clear_filters),
            action = onClearFilters,
            modifier = modifier,
        )
    } else {
        EmptyState(
            glyph = stringResource(R.string.sessions_glyph_empty),
            title = stringResource(R.string.sessions_empty_title),
            description = stringResource(R.string.sessions_empty_description),
            actionLabel = stringResource(R.string.sessions_empty_action),
            action = onStartSession,
            modifier = modifier,
        )
    }
}

/* Stable keys for the two items that are not rows. */
private const val EMPTY_KEY = "empty"
private const val FOOTER_KEY = "footer"
