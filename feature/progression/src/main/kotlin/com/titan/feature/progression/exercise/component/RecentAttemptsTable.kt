package com.titan.feature.progression.exercise.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.titan.core.domain.model.ExerciseAttempt
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatClockValue
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.format.formatDistance
import com.titan.core.ui.format.formatDistanceValue
import com.titan.core.ui.format.formatSet
import com.titan.core.ui.format.formatSetCompact
import com.titan.core.ui.format.formatVolume
import com.titan.core.ui.format.formatVolumeValue
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.core.ui.R as CoreR

/**
 * The lift's recent sessions, as a table: date, sets, top set, volume.
 *
 * ```text
 *  DATE     SETS  TOP       VOL
 *  28 Apr   4     80×5 ★    1,420
 *  24 Apr   4     77×5      1,310
 * ```
 *
 * ### Newest first, where the charts run oldest first
 *
 * A series is read left to right and a list of attempts is read from the top, so the same
 * sessions appear in opposite orders on one screen. That is deliberate rather than an
 * oversight — `ExerciseProgressionUiState.recentAttempts` does the reversing once, so the two
 * cannot fall out of step.
 *
 * ### A table, not rows that happen to line up
 *
 * The header and every row are laid out by [AttemptGrid] against one set of weights, because
 * the whole value of the shape is that `TOP` sits over the top sets. Columns spelled out per
 * row are how a header ends up half a column left of what it names.
 *
 * ### One node per row
 *
 * Each row announces as a sentence — `28 Apr, 4 sets, top set 80 kg × 5, volume 1,420 kg` —
 * rather than as four fragments a screen reader walks between. [formatSet]'s long form is what
 * is spoken even though the cell draws [formatSetCompact]'s: `80×5` is a rendering for a narrow
 * column, not a phrase.
 *
 * @param attempts the sessions to draw, newest first.
 * @param recordSessionId the attempt that broke a record, drawn in the accent with a star, or
 *   **null** when none did. One row at most: the mark means *this one*, and a record is only
 *   ever new in the most recent attempt on screen.
 *
 * ### A timed exercise has no volume column
 *
 * A run or a plank moves nothing, so a column of zero volumes would be the whole table saying
 * nothing happened (#M7-17). Where every attempt on screen is volume-less and timed, the last
 * column is what the attempts *did* instead — see [AttemptMeasure] — and the top set reads as the
 * time it was quoted by. Decided once for the table rather than per row, so the header over the
 * column is true of every cell under it.
 */
@Composable
fun RecentAttemptsTable(
    attempts: List<ExerciseAttempt>,
    modifier: Modifier = Modifier,
    recordSessionId: Long? = null,
) {
    val total = AttemptMeasure.of(attempts)
    TitanCard(modifier = modifier.fillMaxWidth()) {
        AttemptHeader(total = total)
        attempts.forEach { attempt ->
            // Before each row rather than after it, so the table closes on a row and not on a
            // rule drawn just inside the card's own border.
            HairlineDivider()
            AttemptRow(attempt = attempt, isRecord = attempt.sessionId == recordSessionId, total = total)
        }
    }
}

/**
 * The column labels, in the eyebrow role the wireframes give them.
 *
 * Hidden from screen readers in one gesture: every row already says which figure is which, and
 * a header read on its own is four words with no row attached to them — the same call
 * `ChartAxisLabels` makes about a chart's months.
 */
@Composable
private fun AttemptHeader(total: AttemptMeasure) {
    AttemptGrid(
        modifier = Modifier.clearAndSetSemantics { },
        verticalPadding = TitanTheme.spacing.md,
    ) {
        HeaderCell(text = stringResource(R.string.exercise_attempts_date), weight = DATE_WEIGHT)
        HeaderCell(text = stringResource(R.string.exercise_attempts_sets), weight = SETS_WEIGHT)
        HeaderCell(text = stringResource(R.string.exercise_attempts_top), weight = TOP_SET_WEIGHT)
        HeaderCell(
            text = stringResource(
                when (total) {
                    AttemptMeasure.VOLUME -> R.string.exercise_attempts_volume
                    AttemptMeasure.DISTANCE -> R.string.exercise_attempts_distance
                    AttemptMeasure.TIME -> R.string.exercise_attempts_time
                },
            ),
            weight = VOLUME_WEIGHT,
        )
    }
}

/** One session's line. */
@Composable
private fun AttemptRow(
    attempt: ExerciseAttempt,
    isRecord: Boolean,
    total: AttemptMeasure,
) {
    val date = formatDayAndMonth(attempt.finishedAt)
    val missing = stringResource(R.string.exercise_attempts_missing)
    val glyph = stringResource(R.string.exercise_attempts_record_glyph)
    val top = attempt.topSet
    val topSetCell = formatSetCompact(top?.weight, top?.reps, top?.duration, top?.distance) ?: missing
    val topSetSpoken = formatSet(top?.weight, top?.reps, top?.duration, top?.distance) ?: missing
    val (totalCell, totalSpoken) = when (total) {
        AttemptMeasure.VOLUME -> formatVolumeValue(attempt.volume) to formatVolume(attempt.volume)
        AttemptMeasure.DISTANCE -> formatDistanceValue(attempt.distance) to formatDistance(attempt.distance)
        AttemptMeasure.TIME -> formatClockValue(attempt.timedSeconds).let { it to it }
    }

    val spoken = stringResource(
        when {
            total != AttemptMeasure.VOLUME && isRecord -> R.string.exercise_attempt_timed_record_description
            total != AttemptMeasure.VOLUME -> R.string.exercise_attempt_timed_description
            isRecord -> R.string.exercise_attempt_record_description
            else -> R.string.exercise_attempt_description
        },
        date,
        pluralStringResource(R.plurals.exercise_attempts_set_count, attempt.sets, attempt.sets),
        topSetSpoken,
        totalSpoken,
    )

    AttemptGrid(modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Cell(
            text = date,
            weight = DATE_WEIGHT,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Cell(text = formatCount(attempt.sets), weight = SETS_WEIGHT)
        Cell(
            // The star rides in the same cell as the figure rather than in a column of its
            // own: a fifth column would be empty on every row but one, and the mark belongs to
            // the set it is a record for.
            text = if (isRecord) stringResource(CoreR.string.figure_with_unit, topSetCell, glyph) else topSetCell,
            weight = TOP_SET_WEIGHT,
            color = if (isRecord) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isRecord) FontWeight.SemiBold else FontWeight.Normal,
        )
        Cell(text = totalCell, weight = VOLUME_WEIGHT)
    }
}

/**
 * The grid the header and every row are laid out on.
 *
 * Weights rather than fixed widths, so the four columns fill whatever the card gives them and
 * hold the same figures on a 320dp screen as on a 360dp one. `TOP` takes the most because it is
 * the widest cell — `100×12 ★` — and `SETS` the least because it is one digit.
 */
@Composable
private fun AttemptGrid(
    modifier: Modifier = Modifier,
    verticalPadding: Dp = TitanTheme.spacing.lg,
    cells: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TitanTheme.spacing.xl, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        content = cells,
    )
}

/**
 * One cell, in the numeric role.
 *
 * Every column here is a figure or a date, and both are numerics — mono, tabular — so the
 * digits line up down the column rather than shifting with each row's content.
 */
@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        style = TitanTheme.typography.numericSmall,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight),
    )
}

/** One column label, over the cells it names. */
@Composable
private fun RowScope.HeaderCell(
    text: String,
    weight: Float,
) {
    Eyebrow(
        text = text,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.weight(weight),
    )
}

private const val DATE_WEIGHT = 1.1f
private const val SETS_WEIGHT = 0.5f
private const val TOP_SET_WEIGHT = 1.2f
private const val VOLUME_WEIGHT = 1f
