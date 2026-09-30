package com.titan.core.ui.component

import androidx.compose.foundation.BorderStroke
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
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.format.formatDayOfMonth
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.format.weekdayInitial
import com.titan.core.ui.theme.HAIRLINE_WIDTH
import com.titan.core.ui.theme.TitanTheme
import java.time.LocalDate

/**
 * One week as seven cells, the trained days filled in.
 *
 * ```text
 * M   T   W   T   F   S   S
 * 27  28  29  30  1   2   3
 * ▓   ▓   ░   ░   ·   ·   ·
 * ```
 *
 * [TrainingCalendar]'s row, on its own and without the month around it: the home screen shows
 * the week the user is in the middle of, and the stats hub (M5) shows the same seven days over
 * a longer view. Shared for the reason the calendar is — a second implementation is how two
 * screens end up disagreeing about which days count as trained — and built from the same
 * colour pairs, the same radius and the same today ring, so the strip and the grid read as the
 * same instrument at two lengths.
 *
 * ### Three states, not two: trained, skipped, and not yet
 *
 * A day that has not happened is drawn as an outline rather than as a filled cell, because a
 * week is read on the Monday morning it starts as often as on the Sunday night it ends, and a
 * strip that paints the whole week as rest days tells that user they have already lost it.
 * Nothing about Friday is true yet, so Friday says nothing — see [WeekDay.isFuture].
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
 *   opens a week on. Ordinarily seven, but any length draws: each header letter is taken from
 *   the day it sits over, so the letters and the dates agree however many there are.
 */
@Composable
fun WeekStrip(
    days: List<WeekDay>,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs)) {
            // Each letter from its own day rather than from a seven-long list rotated to the
            // week's first one: the locale is not asked a second time — which is how a strip
            // ends up labelling Monday's cell "S" — and a list that is not seven long still
            // gets a letter over every cell rather than seven letters over eight of them.
            days.forEach { day ->
                Text(
                    text = weekdayInitial(day.date.dayOfWeek),
                    style = TitanTheme.typography.numericSmall,
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

        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs)) {
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
private fun RowScope.DayCell(
    day: WeekDay,
    modifier: Modifier = Modifier,
) {
    val trained = day.sessionCount > 0
    val container: Color = when {
        trained -> MaterialTheme.colorScheme.primaryContainer

        // No fill at all for a day that has not happened. The filled `surfaceContainer` cell
        // is a statement — this day went by without a workout — and it is not one the app can
        // make about Friday on a Monday.
        day.isFuture -> Color.Transparent

        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val content: Color = when {
        trained -> MaterialTheme.colorScheme.onPrimaryContainer
        day.isFuture -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.outline
    }
    // Today's ruled ring, or the hairline that gives an unfilled cell its edge back. Never
    // both: today has happened, so it is never the future one.
    val ring: BorderStroke? = when {
        day.isToday -> BorderStroke(TODAY_RING, MaterialTheme.colorScheme.onSurface)
        day.isFuture -> BorderStroke(HAIRLINE_WIDTH, MaterialTheme.colorScheme.outlineVariant)
        else -> null
    }

    val spoken = when {
        trained -> pluralStringResource(
            R.plurals.calendar_day_sessions,
            day.sessionCount,
            formatSessionDate(day.date),
            day.sessionCount,
        )

        // "Fri 1 May 2026, rest day" for a day nobody has had the chance to train on yet is
        // the drawn cell's lie, said out loud.
        day.isFuture -> stringResource(R.string.calendar_day_upcoming, formatSessionDate(day.date))

        else -> stringResource(R.string.calendar_day_rest, formatSessionDate(day.date))
    }

    Box(
        modifier = modifier
            .weight(1f)
            .aspectRatio(1f)
            .background(color = container, shape = MaterialTheme.shapes.extraSmall)
            .then(
                if (ring != null) {
                    Modifier.border(border = ring, shape = MaterialTheme.shapes.extraSmall)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatDayOfMonth(day.date),
            style = TitanTheme.typography.numericSmall,
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
 * @param isFuture the day has not happened yet: drawn as an outline rather than as a filled
 *   cell, and announced as upcoming rather than as a rest day. Passed for the same reason
 *   [isToday] is, and separately from it because "not trained" and "not yet" are different
 *   facts that a `sessionCount` of zero cannot tell apart.
 */
@Immutable
data class WeekDay(
    val date: LocalDate,
    val sessionCount: Int = 0,
    val isToday: Boolean = false,
    val isFuture: Boolean = false,
)

/** Twice the hairline, so today reads as marked rather than as a cell with a border. */
private val TODAY_RING = 2.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private val PREVIEW_MONDAY: LocalDate = LocalDate.of(2026, 4, 27)

/** Trained on Monday and Wednesday, today is Wednesday, and the rest of the week is ahead. */
private val PREVIEW_WEEK: List<WeekDay> = List(7) { offset ->
    val date = PREVIEW_MONDAY.plusDays(offset.toLong())
    WeekDay(
        date = date,
        sessionCount = if (offset == 0 || offset == 2) 1 else 0,
        isToday = offset == 2,
        isFuture = offset > 2,
    )
}

@Preview(name = "Week strip", widthDp = 360)
@Composable
fun WeekStripPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        WeekStripCatalogue()
    }
}

@Preview(name = "Week strip, dark", widthDp = 360)
@Composable
fun WeekStripDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        WeekStripCatalogue()
    }
}

@Composable
private fun WeekStripCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        WeekStrip(days = PREVIEW_WEEK)
        // Monday morning: one day to be judged on and six the week has not reached.
        WeekStrip(
            days = PREVIEW_WEEK.mapIndexed { offset, day ->
                day.copy(sessionCount = 0, isToday = offset == 0, isFuture = offset > 0)
            },
        )
        // The week from its far end, where every cell has happened and three were skipped.
        WeekStrip(
            days = PREVIEW_WEEK.mapIndexed { offset, day ->
                day.copy(isToday = offset == 6, isFuture = false)
            },
        )
    }
}
