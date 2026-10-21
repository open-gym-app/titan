package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
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
import com.titan.core.ui.format.firstDayOfWeek
import com.titan.core.ui.format.formatDayOfMonth
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.format.weekdayInitials
import com.titan.core.ui.test.TitanTestTags
import com.titan.core.ui.theme.TitanTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * A month of training, as a grid of days shaded by how much was done on each.
 *
 * ```text
 * M  T  W  T  F  S  S
 *       ▢  ▢  ■  ▢  ▩
 * ▩  ▢  ■  ▢  ▢  ▩  ▢
 * ```
 *
 * Shared rather than owned by the sessions screen because the stats hub draws the same grid
 * (M5's "Calendar heatmap · frequency, rest days"), and a second implementation of it is how
 * two screens end up disagreeing about which days count as trained.
 *
 * ### Two shades of trained, not five
 *
 * A heatmap with a five-step ramp needs five colours that are all recognisably the same
 * colour, and this design has one accent and a set of neutrals — the ramp would have to be
 * five alphas of the accent, which is five pairs no contrast checker has ever looked at. Two
 * steps say what the grid is for: a day was trained, and it was one of the month's harder
 * ones. Both are existing role pairs (`primaryContainer`/`onPrimaryContainer` and
 * `primary`/`onPrimary`), so both are already asserted by `ColorSchemeContrastTest`.
 *
 * ### The week starts where the locale says
 *
 * The wireframes draw `M T W T F S S`, which is right in Britain and wrong in the United
 * States. [weekStart] defaults to the formatting locale's answer, and both the header and the
 * leading blanks are laid out from that one value — see
 * [com.titan.core.ui.format.weekdayInitials].
 *
 * @param month the page being shown. The grid holds exactly its days: no trailing days of
 *   the previous month and no leading ones of the next, because a heatmap that shaded a day
 *   twice would be counting a session twice to anybody reading it.
 * @param days what was trained, keyed by day of month. A day with no entry was a rest day.
 * @param weekStart the day the locale's week opens on. A parameter rather than a constant so
 *   a test can pin it — a grid whose leading blanks depend on the machine's locale is a
 *   screenshot that changes when CI does.
 * @param today drawn with a ruled outline, or null when the month on screen is not the one
 *   today is in. Passed rather than read off a clock in here, for the reason [SessionRow]
 *   takes `isToday`.
 * @param onDayClick opens a day. Only *trained* days take it — a rest day has nothing to
 *   filter a list down to, and a cell that ripples and then shows an empty list is a control
 *   that lied.
 */
@Composable
fun TrainingCalendar(
    month: YearMonth,
    days: Map<Int, TrainingDay>,
    modifier: Modifier = Modifier,
    today: LocalDate? = null,
    weekStart: DayOfWeek = firstDayOfWeek(),
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    // How many blanks open the grid: the distance from the week's first day to the day the
    // month lands on, wrapped into 0..6. `DayOfWeek.value` is ISO (Monday = 1), so the
    // arithmetic is the same whichever day the locale starts on.
    val leadingBlanks = Math.floorMod(
        month.atDay(1).dayOfWeek.value - weekStart.value,
        DAYS_PER_WEEK,
    )
    val cells = leadingBlanks + month.lengthOfMonth()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs)) {
            weekdayInitials(weekStart).forEach { initial ->
                Text(
                    text = initial,
                    style = TitanTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    // The seven initials are a legend for the grid, not seven things to
                    // swipe through: the cells below announce their own full dates.
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics { },
                )
            }
        }

        for (rowStart in 0 until cells step DAYS_PER_WEEK) {
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs)) {
                for (cell in rowStart until rowStart + DAYS_PER_WEEK) {
                    val dayOfMonth = cell - leadingBlanks + 1
                    if (cell < leadingBlanks || dayOfMonth > month.lengthOfMonth()) {
                        // A blank still takes a cell's width, so the columns line up with
                        // the header above them.
                        EmptyCell()
                    } else {
                        val date = month.atDay(dayOfMonth)
                        DayCell(
                            date = date,
                            day = days[dayOfMonth],
                            isToday = date == today,
                            onClick = onDayClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One day.
 *
 * The whole cell is one node carrying its date and its count, so TalkBack reads
 * "Mon 28 Apr 2026, 2 sessions" rather than a bare number in a grid of thirty other bare
 * numbers. The description is set on the same node the click lives on rather than *replacing*
 * that node's semantics, which is what keeps a tappable day tappable to a screen reader —
 * `clearAndSetSemantics` here would wipe the click action along with the number.
 */
@Composable
private fun RowScope.DayCell(
    date: LocalDate,
    day: TrainingDay?,
    isToday: Boolean,
    onClick: ((LocalDate) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val container: Color = when (day?.load) {
        TrainingLoad.HEAVY -> MaterialTheme.colorScheme.primary
        TrainingLoad.LIGHT -> MaterialTheme.colorScheme.primaryContainer
        null -> MaterialTheme.colorScheme.surfaceContainer
    }
    val content: Color = when (day?.load) {
        TrainingLoad.HEAVY -> MaterialTheme.colorScheme.onPrimary
        TrainingLoad.LIGHT -> MaterialTheme.colorScheme.onPrimaryContainer
        null -> MaterialTheme.colorScheme.outline
    }

    val spoken = if (day == null) {
        stringResource(R.string.calendar_day_rest, formatSessionDate(date))
    } else {
        pluralStringResource(
            R.plurals.calendar_day_sessions,
            day.sessionCount,
            formatSessionDate(date),
            day.sessionCount,
        )
    }
    // Only a *trained* day opens: a rest day has nothing to filter a list down to, and a
    // cell that ripples and then shows an empty list is a control that lied.
    val open = onClick?.takeIf { day != null }
    val openLabel = stringResource(R.string.calendar_day_open)

    Box(
        modifier = modifier
            .weight(1f)
            .aspectRatio(1f)
            .background(color = container, shape = MaterialTheme.shapes.extraSmall)
            .then(
                if (isToday) {
                    Modifier.border(
                        width = TODAY_RING,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (open != null) {
                    Modifier.clickable(onClickLabel = openLabel) { open(date) }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            // Seven columns in a card come to about 40dp each on a phone, under the 48dp a tap
            // target is owed and not fixable without seven fewer columns. The one accepted
            // deviation in the accessibility pass; the tag is what scopes it to exactly these
            // cells — see [TitanTestTags.CALENDAR_DAY] and docs/ACCESSIBILITY.md.
            .testTag(TitanTestTags.CALENDAR_DAY),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatDayOfMonth(date),
            style = TitanTheme.typography.numericSmall,
            color = content,
            maxLines = 1,
        )
    }
}

/** An empty cell, holding a column's width so the grid stays square. */
@Composable
private fun RowScope.EmptyCell() {
    Box(modifier = Modifier.weight(1f).aspectRatio(1f))
}

/**
 * One day's training, as the grid needs it.
 *
 * @param sessionCount how many sessions were logged that day. Two is unusual and worth
 *   announcing; the grid does not draw it, because a second glyph in a 40dp cell is not
 *   legible and the shade already says the day was heavy.
 * @param load how the day compares to the rest of the month — see [TrainingLoad].
 */
@Immutable
data class TrainingDay(
    val sessionCount: Int,
    val load: TrainingLoad,
)

/**
 * How hard a day was, *relative to the month it is in*.
 *
 * Relative rather than absolute on purpose: a heatmap exists to show the shape of a month,
 * and an absolute threshold in kilograms would paint a beginner's whole year light and a
 * powerlifter's whole year dark, which tells neither of them anything. The consequence worth
 * knowing is that the same session can be light in one month and heavy in another, so the
 * shade is not a figure to compare across pages.
 */
enum class TrainingLoad {

    /** A trained day below the month's midpoint. */
    LIGHT,

    /** A trained day at or above half of the month's heaviest. */
    HEAVY,
}

private const val DAYS_PER_WEEK = 7

/** Twice the hairline, so today reads as marked rather than as a cell with a border. */
private val TODAY_RING = 2.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private const val PREVIEW_YEAR = 2026
private const val PREVIEW_MONTH_OF_YEAR = 4
private const val PREVIEW_TODAY = 28

private val PREVIEW_MONTH: YearMonth = YearMonth.of(PREVIEW_YEAR, PREVIEW_MONTH_OF_YEAR)
private val PREVIEW_TODAY_DATE: LocalDate = PREVIEW_MONTH.atDay(PREVIEW_TODAY)

private val PREVIEW_DAYS: Map<Int, TrainingDay> = mapOf(
    2 to TrainingDay(1, TrainingLoad.LIGHT),
    4 to TrainingDay(1, TrainingLoad.HEAVY),
    7 to TrainingDay(1, TrainingLoad.LIGHT),
    9 to TrainingDay(1, TrainingLoad.HEAVY),
    11 to TrainingDay(1, TrainingLoad.LIGHT),
    14 to TrainingDay(1, TrainingLoad.HEAVY),
    16 to TrainingDay(1, TrainingLoad.LIGHT),
    18 to TrainingDay(2, TrainingLoad.HEAVY),
    21 to TrainingDay(1, TrainingLoad.LIGHT),
    23 to TrainingDay(1, TrainingLoad.HEAVY),
    25 to TrainingDay(1, TrainingLoad.LIGHT),
    28 to TrainingDay(1, TrainingLoad.HEAVY),
    30 to TrainingDay(1, TrainingLoad.LIGHT),
)

@Preview(name = "Training calendar", widthDp = 360)
@Composable
fun TrainingCalendarPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TrainingCalendar(
            month = PREVIEW_MONTH,
            days = PREVIEW_DAYS,
            today = PREVIEW_TODAY_DATE,
            onDayClick = {},
            modifier = Modifier.padding(TitanTheme.spacing.screen),
        )
    }
}

@Preview(name = "Training calendar - dark", widthDp = 360)
@Composable
fun TrainingCalendarDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        TrainingCalendar(
            month = PREVIEW_MONTH,
            days = PREVIEW_DAYS,
            today = PREVIEW_TODAY_DATE,
            onDayClick = {},
            modifier = Modifier.padding(TitanTheme.spacing.screen),
        )
    }
}

@Preview(name = "Training calendar - empty month", widthDp = 360)
@Composable
fun TrainingCalendarEmptyPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TrainingCalendar(
            month = PREVIEW_MONTH,
            days = emptyMap(),
            modifier = Modifier.padding(TitanTheme.spacing.screen),
        )
    }
}
