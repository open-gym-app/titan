package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatDayOfMonth
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.format.weekdayInitials
import com.opengym.core.ui.theme.OpenGymTheme
import java.time.LocalDate

/**
 * One week as seven cells, the trained days filled in.
 *
 * ```text
 * M   T   W   T   F   S   S
 * 27  28  29  30  1   2   3
 * ▓   ▓   ░   ░   ░   ░   ░
 * ```
 *
 * [TrainingCalendar]'s row, on its own and without the month around it: the home screen shows
 * the week the user is in the middle of, and the stats hub (M5) shows the same seven days over
 * a longer view. Shared for the reason the calendar is — a second implementation is how two
 * screens end up disagreeing about which days count as trained — and built from the same
 * colour pairs, the same radius and the same today ring, so the strip and the grid read as the
 * same instrument at two lengths.
 *
 * ### One shade of trained, where the calendar has two
 *
 * A month's grid can afford a ramp: thirty days give it something to compare against, and
 * [TrainingLoad] is what makes the shape of a month legible. Seven days are not a
 * distribution — the heaviest of four sessions is whichever one happened to be heaviest, which
 * is not a fact worth painting the accent on — so a trained day is a trained day here, in
 * `primaryContainer`. The flat accent is left for the one control on the screen that matters
 * right now, which on Home is the button that resumes a workout.
 *
 * @param days the week, in the order it is drawn, starting on whatever day the caller's locale
 *   opens a week on. Exactly seven; the header letters are derived from the first one's
 *   weekday, so a caller that hands over a partial week gets a strip whose letters and dates
 *   still agree with each other.
 */
@Composable
fun WeekStrip(
    days: List<WeekDay>,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
            // Taken from the week's own first day rather than from the locale a second time:
            // the caller already decided where the week starts when it built the list, and
            // asking again is how a strip ends up labelling Monday's cell "S".
            weekdayInitials(days.first().date.dayOfWeek).take(days.size).forEach { initial ->
                Text(
                    text = initial,
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    // The seven letters are a legend for the cells, not seven things to swipe
                    // through: each cell below announces its own full date.
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics { },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
            days.forEach { day -> DayCell(day) }
        }
    }
}

/**
 * One day.
 *
 * The whole cell is one node carrying its date and its count, so TalkBack reads
 * "Mon 28 Apr 2026, 2 sessions" rather than a bare number in a row of seven bare numbers —
 * the same sentence, from the same strings, that a calendar cell announces.
 */
@Composable
private fun RowScope.DayCell(day: WeekDay, modifier: Modifier = Modifier) {
    val trained = day.sessionCount > 0
    val container: Color = if (trained) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content: Color = if (trained) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.outline
    }

    val spoken = if (trained) {
        pluralStringResource(
            R.plurals.calendar_day_sessions,
            day.sessionCount,
            formatSessionDate(day.date),
            day.sessionCount,
        )
    } else {
        stringResource(R.string.calendar_day_rest, formatSessionDate(day.date))
    }

    Box(
        modifier = modifier
            .weight(1f)
            .aspectRatio(1f)
            .background(color = container, shape = MaterialTheme.shapes.extraSmall)
            .then(
                if (day.isToday) {
                    Modifier.border(
                        width = TODAY_RING,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatDayOfMonth(day.date),
            style = OpenGymTheme.typography.numericSmall,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * One day of a week, as the strip needs it.
 *
 * @param sessionCount how many sessions were logged that day. Zero is a rest day. The figure
 *   is announced rather than drawn, for the reason [TrainingDay] gives: a second glyph in a
 *   40dp cell is not legible, and the fill already says the day was trained.
 * @param isToday draws the cell with a ruled outline. Passed rather than read off a clock in
 *   here, for the reason [SessionRow] takes `isToday` — a component that read the date itself
 *   would render differently on the day a screenshot was recorded and every day after it.
 */
@Immutable
data class WeekDay(
    val date: LocalDate,
    val sessionCount: Int = 0,
    val isToday: Boolean = false,
)

/** Twice the hairline, so today reads as marked rather than as a cell with a border. */
private val TODAY_RING = 2.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private val PREVIEW_MONDAY: LocalDate = LocalDate.of(2026, 4, 27)

/** Trained on Monday, Wednesday and twice on Saturday; today is Wednesday. */
private val PREVIEW_WEEK: List<WeekDay> = List(7) { offset ->
    val date = PREVIEW_MONDAY.plusDays(offset.toLong())
    WeekDay(
        date = date,
        sessionCount = when (offset) {
            0, 2 -> 1
            5 -> 2
            else -> 0
        },
        isToday = offset == 2,
    )
}

@Preview(name = "Week strip", widthDp = 360)
@Composable
fun WeekStripPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        WeekStripCatalogue()
    }
}

@Preview(name = "Week strip, dark", widthDp = 360)
@Composable
fun WeekStripDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        WeekStripCatalogue()
    }
}

@Composable
private fun WeekStripCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        WeekStrip(days = PREVIEW_WEEK)
        // A week nothing has happened in yet, which is what Monday morning looks like.
        WeekStrip(days = PREVIEW_WEEK.map { it.copy(sessionCount = 0) })
    }
}
