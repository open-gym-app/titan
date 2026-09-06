package com.opengym.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.format.firstDayOfWeek
import com.opengym.core.ui.format.formattingLocale
import com.opengym.core.ui.format.weekdayInitial
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * The seven days of the week, as a row of cells that are each on or off.
 *
 * ### A segmented row, not seven chips
 *
 * [OpenGymFilterChip] is the app's other multi-select, and it is the wrong shape here. A chip
 * row is a *list* — it wraps, it scrolls, the chips are as wide as their labels — and the week
 * is none of those things: it is seven fixed positions in a known order, and the whole value of
 * seeing it is reading the pattern. `M W F` lit out of seven evenly spaced cells is a training
 * split at a glance; the same three as chips is three words.
 *
 * The cells therefore share the width equally and stay put when they are deselected, which is
 * also what makes the row a stable tap target: the day under your thumb does not move because
 * the day beside it changed.
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
 *   [OpenGymFilterChip] gives, and for the same reason: a greyed-out schedule is harder to read
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

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
    ) {
        week.forEach { day ->
            WeekdayCell(
                day = day,
                locale = locale,
                isSelected = day in selected,
                enabled = enabled,
                onToggle = { onToggle(day, it) },
            )
        }
    }
}

/**
 * One day.
 *
 * `weight(1f)` rather than a fixed size, so the row fills whatever it is given and the seven
 * cells stay identical at every screen width and font scale. The height floor is the tap
 * target: on a 360dp phone inside a card the cells come out around 40dp wide, which is under
 * Material's 48dp minimum in one dimension, so the other dimension is what makes the target
 * large enough to hit with a thumb.
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
            .heightIn(min = CELL_MIN_HEIGHT)
            .toggleable(
                value = isSelected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onToggle,
            )
            .semantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.small,
        // The same two states [OpenGymFilterChip] draws, and deliberately the same colours: a
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
                style = OpenGymTheme.typography.eyebrow,
                modifier = Modifier.padding(OpenGymTheme.spacing.md),
            )
        }
    }
}

/** The seven days in order, starting from [start]. */
private fun weekFrom(start: DayOfWeek): List<DayOfWeek> =
    (0 until DAYS_PER_WEEK).map { offset -> start.plus(offset.toLong()) }

/**
 * The floor under a cell's height, which is what makes it a 48dp tap target.
 *
 * Seven cells across a 360dp phone, inside a card, come to roughly 40dp wide each — under
 * Material's minimum in that dimension and not fixable there without letting the row scroll,
 * which would cost the whole point of seeing the week at once.
 */
private val CELL_MIN_HEIGHT: Dp = 48.dp

private const val DAYS_PER_WEEK = 7

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Weekday picker", widthDp = 360)
@Composable
fun WeekdayPickerPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        WeekdayPickerCatalogue()
    }
}

@Preview(name = "Weekday picker, dark", widthDp = 360)
@Composable
fun WeekdayPickerDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        WeekdayPickerCatalogue()
    }
}

@Composable
private fun WeekdayPickerCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        WeekdayPicker(
            selected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            onToggle = { _, _ -> },
        )
        WeekdayPicker(selected = DayOfWeek.entries.toSet(), onToggle = { _, _ -> })
        WeekdayPicker(selected = emptySet(), onToggle = { _, _ -> }, enabled = false)
    }
}
