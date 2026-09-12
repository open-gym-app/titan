package com.opengym.feature.history.sessions.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.OpenGymFilterChip
import com.opengym.core.ui.component.SheetDragHandle
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.sessions.DateFilter
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The window of time the history is narrowed to, in a bottom sheet.
 *
 * Four windows and a picker, rather than a picker alone. "The last month" is the question a
 * lifter actually asks of their history and it should not cost six taps in a calendar grid;
 * the picker is there for the one case the presets cannot express — a training block with
 * ends the user remembers.
 *
 * The presets are *rolling* rather than resolved when they are picked (see
 * [DateFilter.LastDays]), so a filter left on overnight still means the last thirty days in
 * the morning and not the thirty days that ended yesterday.
 *
 * Selecting applies immediately and leaves the sheet up, matching `LibraryFilterSheet`: the
 * list behind it re-narrows as each preset is tried, which is what lets somebody find the
 * window they wanted without closing and reopening the control.
 *
 * [DateFilterPanel] is the part with the layout in it, so a test can drive the presets without
 * a sheet's animation and window behaviour in the way — the split `LibraryFilterSheet` makes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateFilterSheet(
    filter: DateFilter,
    onFilterSelected: (DateFilter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // A half-expanded stop would cut the presets in half, which is the whole content.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { SheetDragHandle() },
    ) {
        DateFilterPanel(
            filter = filter,
            onFilterSelected = onFilterSelected,
            onDone = onDismiss,
        )
    }
}

/**
 * The presets, the picker, and the way out.
 *
 * One selection rather than a set: the windows are alternatives, and "the last week or the
 * last year" is not a question. The chips announce themselves as such — see
 * `OpenGymFilterChip`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateFilterPanel(
    filter: DateFilter,
    onFilterSelected: (DateFilter) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OpenGymTheme.spacing.screen)
            .padding(bottom = OpenGymTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        Text(
            text = stringResource(R.string.sessions_dates),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Wrapped rather than in a scrolling row, for `ChipSection`'s reason: five windows in
        // a sideways scroller is a control that hides its own options, and a user who cannot
        // see "all time" is a user who cannot get back to it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
        ) {
            OpenGymFilterChip(
                label = stringResource(R.string.sessions_dates_all),
                selected = filter == DateFilter.All,
                onClick = { onFilterSelected(DateFilter.All) },
            )
            PRESET_WINDOWS.forEach { days ->
                OpenGymFilterChip(
                    label = pluralStringResource(R.plurals.sessions_dates_last_days, days, days),
                    selected = filter == DateFilter.LastDays(days),
                    onClick = { onFilterSelected(DateFilter.LastDays(days)) },
                )
            }
            OpenGymFilterChip(
                // A chosen window labels itself with its own ends, so the chip that opens the
                // picker is also the one that says what came out of it. A separate "custom"
                // chip beside a range nobody can read is two controls for one fact.
                label = (filter as? DateFilter.Between)?.label()
                    ?: stringResource(R.string.sessions_dates_custom),
                selected = filter is DateFilter.Between,
                onClick = { picking = true },
            )
        }

        Button(
            onClick = onDone,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.sessions_dates_done),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (picking) {
        DateRangeDialog(
            initial = filter as? DateFilter.Between,
            onConfirm = {
                onFilterSelected(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Material's own range picker, in a dialog.
 *
 * Not hand-rolled, unlike [com.opengym.core.ui.component.TrainingCalendar]: that one is a
 * *read* — a heatmap with no selection model and no keyboard handling — and this is a two-ended
 * date entry with a text mode, an input validator and a locale-aware header, none of which is
 * worth rebuilding to change the corner radii on.
 *
 * The picker works in UTC epoch millis and this app stores UTC too, but the two are not the
 * same thing: a date picked here is a *calendar day*, and converting it through the device's
 * zone would move it by a day for anybody far enough east or west. So the conversion is
 * pinned to [ZoneOffset.UTC] on the way in and out, and the comparison against a session's
 * own local day happens on `LocalDate`s where a zone has already been applied — see
 * `SessionsViewModel`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    initial: DateFilter.Between?,
    onConfirm: (DateFilter.Between) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toUtcMillis(),
        initialSelectedEndDateMillis = initial?.end?.toUtcMillis(),
    )
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (start != null) {
                        // A single tap selects a start and no end, which is a legal thing to
                        // mean: "that day". Requiring the second tap would make the picker
                        // refuse the commonest reason to open it.
                        onConfirm(
                            DateFilter.Between(
                                start = start.toUtcDate(),
                                end = (end ?: start).toUtcDate(),
                            ),
                        )
                    }
                },
                enabled = start != null,
            ) {
                Text(text = stringResource(R.string.sessions_dates_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.sessions_dates_cancel))
            }
        },
    ) {
        DateRangePicker(state = pickerState)
    }
}

/** `Mon 28 Apr 2026 – Sun 3 May 2026`, or just the one day when both ends match. */
@Composable
private fun DateFilter.Between.label(): String =
    if (start == end) {
        formatSessionDate(start)
    } else {
        stringResource(R.string.sessions_dates_range, formatSessionDate(start), formatSessionDate(end))
    }

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * The windows worth a chip: a week, a month, a training block.
 *
 * Days rather than months, so the arithmetic is one subtraction and the label is one plural —
 * "last 30 days" is also the more honest phrasing, since nobody means the 28th through the
 * 31st when they say "this month".
 */
private val PRESET_WINDOWS = listOf(7, 30, 90)

// ─── Previews ───────────────────────────────────────────────────────────────────────────

/** A whole month, picked by hand — the one shape the presets cannot express. */
private val PREVIEW_RANGE = DateFilter.Between(
    start = YearMonth.of(2026, 4).atDay(1),
    end = YearMonth.of(2026, 4).atEndOfMonth(),
)

@Preview(name = "Date filter", showBackground = true)
@Composable
fun DateFilterPanelPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        DateFilterPanel(filter = DateFilter.All, onFilterSelected = {}, onDone = {})
    }
}

@Preview(name = "Date filter - a window set", showBackground = true)
@Composable
fun DateFilterPanelSelectedPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        DateFilterPanel(
            filter = PREVIEW_RANGE,
            onFilterSelected = {},
            onDone = {},
        )
    }
}
