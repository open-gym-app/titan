package com.titan.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.format.firstDayOfWeek
import com.titan.core.ui.format.formattingLocale
import com.titan.core.ui.format.weekdayInitial
import com.titan.core.ui.theme.HAIRLINE_WIDTH
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The seven days of the week, as a row of cells that are each on or off.
 *
 * ### A segmented row, not seven chips
 *
 * [TitanFilterChip] is the app's other multi-select, and it is the wrong shape here. A chip
 * row is a *list* — it wraps, it scrolls, the chips are as wide as their labels — and the week
 * is none of those things: it is seven fixed positions in a known order, and the whole value of
 * seeing it is reading the pattern. `M W F` lit out of seven evenly spaced cells is a training
 * split at a glance; the same three as chips is three words.
 *
 * The cells therefore share the width equally and stay put when they are deselected, which is
 * also what makes the row a stable tap target: the day under your thumb does not move because
 * the day beside it changed.
 *
 * ### Two lines where seven squares do not fit
 *
 * A cell is a tap target, so it is 48dp both ways — not tall and narrow. Seven of those with
 * their gaps need 372dp, and a phone's card gives the row about 300: on every phone the week
 * would come out as seven 40dp cells, which Accessibility Scanner reports as an error on each.
 * So the row measures itself first and, where seven do not fit, wraps at four — `M T W T` over
 * `F S S`, every cell the same width, the second line left-aligned so the fourth column stays
 * where it was. The week is still read in order and the pattern is still a pattern; what it is
 * not is seven cells too small to hit. A tablet, or a phone in landscape, gets the single line.
 *
 * ### It starts on the locale's own first day
 *
 * `M T W T F S S` is right in Britain and wrong in the United States. [firstDayOfWeek] answers
 * it, the same way `TrainingCalendar`'s header does — the two are the same week and should not
 * disagree about where it begins.
 *
 * ### Every cell announces its day in full
 *
 * The drawn label is one letter, and TalkBack reading "M, checkbox, checked" tells nobody
 * anything. The [contentDescription] is the day's full name in the composition's locale, which
 * is also what makes the row testable by day rather than by initial — two of the seven letters
 * are ambiguous in English and all seven are in some locales.
 *
 * @param selected the days that are on. Order is not read; the row draws the week's order.
 * @param onToggle a day was tapped, with what it should become.
 * @param enabled false leaves the row readable and inert — the same treatment
 *   [TitanFilterChip] gives, and for the same reason: a greyed-out schedule is harder to read
 *   than a live one, and the row is still saying which days are chosen.
 */
@Composable
fun WeekdayPicker(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val locale = formattingLocale()
    val week = remember(locale) { weekFrom(firstDayOfWeek(locale)) }
    val gap = TitanTheme.spacing.sm

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val singleLine = maxWidth >= MIN_TOUCH_TARGET * DAYS_PER_WEEK + gap * (DAYS_PER_WEEK - 1)
        val perLine = if (singleLine) DAYS_PER_WEEK else WRAPPED_DAYS_PER_LINE

        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            week.chunked(perLine).forEach { line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    line.forEach { day ->
                        WeekdayCell(
                            day = day,
                            locale = locale,
                            isSelected = day in selected,
                            enabled = enabled,
                            onToggle = { onToggle(day, it) },
                        )
                    }
                    // The short line keeps the long line's columns: the cells share the width
                    // with as many empties as there are days missing, so `F` sits under `M`.
                    repeat(perLine - line.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One day.
 *
 * `weight(1f)` rather than a fixed size, so the line fills whatever it is given and its cells
 * stay identical at every screen width and font scale. The height floor is the tap target's
 * other dimension; the width is what [WeekdayPicker] wraps to guarantee.
 */
@Composable
private fun RowScope.WeekdayCell(
    day: DayOfWeek,
    locale: Locale,
    isSelected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val spoken = day.getDisplayName(TextStyle.FULL, locale)

    Surface(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = MIN_TOUCH_TARGET)
            .toggleable(
                value = isSelected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onToggle,
            )
            .semantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.small,
        // The same two states [TitanFilterChip] draws, and deliberately the same colours: a
        // selected day and a selected filter are one idea, and a second treatment for it would
        // be the design system disagreeing with itself.
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            width = HAIRLINE_WIDTH,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = weekdayInitial(day, locale),
                // The eyebrow role rather than a body one: this is a letter standing for a
                // word, drawn in the mono face the rest of the app's codes and figures use,
                // and it lines up with the initials across the calendar header for that reason.
                style = TitanTheme.typography.eyebrow,
                modifier = Modifier.padding(TitanTheme.spacing.md),
            )
        }
    }
}

/** The seven days in order, starting from [start]. */
private fun weekFrom(start: DayOfWeek): List<DayOfWeek> =
    (0 until DAYS_PER_WEEK).map { offset -> start.plus(offset.toLong()) }

private const val DAYS_PER_WEEK = 7

/**
 * How the week breaks when it has to: four and three. Four is the most that fit at 48dp in the
 * ~300dp a phone's card gives the row, and it puts the break after Thursday in a Monday-first
 * week, which is where a working week already pauses.
 */
private const val WRAPPED_DAYS_PER_LINE = 4

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Weekday picker", widthDp = 360)
@Composable
fun WeekdayPickerPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        WeekdayPickerCatalogue()
    }
}

@Preview(name = "Weekday picker, dark", widthDp = 360)
@Composable
fun WeekdayPickerDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        WeekdayPickerCatalogue()
    }
}

/** The one line the row keeps where it has the room — a tablet, or a phone on its side. */
@Preview(name = "Weekday picker, wide", widthDp = 480)
@Composable
fun WeekdayPickerWidePreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(TitanTheme.spacing.screen),
        ) {
            WeekdayPicker(
                selected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                onToggle = { _, _ -> },
            )
        }
    }
}

@Composable
private fun WeekdayPickerCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        WeekdayPicker(
            selected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            onToggle = { _, _ -> },
        )
        WeekdayPicker(selected = DayOfWeek.entries.toSet(), onToggle = { _, _ -> })
        WeekdayPicker(selected = emptySet(), onToggle = { _, _ -> }, enabled = false)
    }
}
