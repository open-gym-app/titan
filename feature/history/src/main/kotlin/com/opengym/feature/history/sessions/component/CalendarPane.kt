package com.opengym.feature.history.sessions.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.StatChip
import com.opengym.core.ui.component.TrainingCalendar
import com.opengym.core.ui.format.formatAsLength
import com.opengym.core.ui.format.formatMonthAndYear
import com.opengym.core.ui.format.formatVolumeValue
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.sessions.CalendarPage
import java.time.LocalDate

/**
 * The month as a heatmap, with what it added up to under it.
 *
 * ### It does not obey the history's filters
 *
 * The grid is a way of reading a *month*, and the history's search and date window are a way
 * of reading a list. A heatmap that dimmed as somebody typed in the search box would be
 * answering a question nobody asked of it — and the tab's own navigation is the month arrows,
 * which a date filter would then be silently fighting. Tapping a day sets the filter and
 * hands the user to the history, which is where a filtered list belongs.
 *
 * ### Not lazy
 *
 * A month is six rows of seven cells and three figures under them. There is nothing here for a
 * `LazyColumn` to defer, so the pane scrolls as a plain `Column` — and its scroll state is
 * hoisted for the same reason the two lists' are: only one tab is composed at a time, and a
 * state remembered in here would be discarded the moment the user switched away.
 */
@Composable
internal fun CalendarPane(
    page: CalendarPage,
    scrollState: ScrollState,
    onDayClick: (LocalDate) -> Unit,
    onMonthChange: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = OpenGymTheme.spacing.screen)
            .padding(bottom = OpenGymTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        MonthNavigation(page = page, onMonthChange = onMonthChange)

        TrainingCalendar(
            month = page.month,
            days = page.days,
            today = page.today,
            onDayClick = onDayClick,
        )

        if (page.sessionCount == 0) {
            Text(
                text = stringResource(R.string.sessions_calendar_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            MonthTotals(page = page)
        }

        // The legend is prose rather than a swatch row: two shades need a sentence to explain
        // what they are *relative to*, and a row of coloured squares beside the words "light"
        // and "heavy" would not carry that at all.
        Text(
            text = stringResource(R.string.sessions_calendar_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * The month, with a way either side of it.
 *
 * Forward is not stopped at the current month. A calendar that refused to page into next
 * month would be enforcing a rule the user has no reason to expect from a grid of dates, and
 * an empty month ahead is self-explanatory — it says so under the grid.
 */
@Composable
private fun MonthNavigation(
    page: CalendarPage,
    onMonthChange: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = OpenGymTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { onMonthChange(false) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.sessions_calendar_previous),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatMonthAndYear(page.month),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = { onMonthChange(true) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.sessions_calendar_next),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the month came to: sessions, time under the bar, volume.
 *
 * Chips rather than the four-up [com.opengym.core.ui.component.SessionStatGrid], because
 * these are context for the grid above rather than the point of the screen — the same
 * distinction `StatChip` and `StatTile` are documented by.
 */
@Composable
private fun MonthTotals(page: CalendarPage, modifier: Modifier = Modifier) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        // Wrapping rather than a `Row`: three labelled chips are already within a few dp of
        // 360, and a month of long sessions ("12h 40m") or a larger font scale puts the last
        // one past the edge — where it does not shrink, it clips, and `19,500 kg` becomes
        // `19,500 k`. A second line is the right answer for a card that is context anyway.
        FlowRow(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
        ) {
            StatChip(
                value = page.sessionCount.toString(),
                label = stringResource(R.string.sessions_calendar_stat_sessions),
            )
            StatChip(
                value = page.totalDuration.formatAsLength(),
                label = stringResource(R.string.sessions_calendar_stat_time),
            )
            StatChip(
                // Labelled with the unit rather than the word "volume": the figure is already
                // a weight, and `19,500 kg` says what `19,500 volume` does not.
                value = formatVolumeValue(page.totalVolume),
                label = LocalUnitSystem.current.weightSuffix,
            )
        }
    }
}
