package com.titan.feature.session.summary.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PrBadge
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.label
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.format.formatWeight
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R
import com.titan.feature.session.summary.SummaryExerciseRow
import com.titan.core.ui.R as CoreR

/**
 * What was actually performed, one exercise to a row, in the order it was done.
 *
 * The list the stat tile above it is counting, which is why it holds exactly the exercises
 * that contributed a completed set — see `SummaryExerciseRow`. An exercise that was planned
 * and skipped is not in either.
 *
 * Rows rather than cards: the sequence is the point, and eight bordered boxes down a screen
 * reads as eight unrelated things instead of as one workout.
 */
@Composable
internal fun ExerciseBreakdownCard(
    exercises: List<SummaryExerciseRow>,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth()) {
        exercises.forEachIndexed { index, row ->
            if (index > 0) HairlineDivider()
            ExerciseRow(position = index + 1, row = row)
        }
    }
}

/**
 * `1  Bench Press [PR]` over `4 × 8-10 · 80 kg max`.
 *
 * The index is drawn rather than left to the reader's counting because the order *is* the
 * information — a session is a sequence, and "which one did I open with" is a question the
 * numbers answer without being read.
 *
 * Announced as one node. An index, a name, a badge and a line of abbreviations are four nodes
 * that a screen reader walks as four unrelated fragments; the description says the same row as
 * a sentence, with the words the figures abbreviate.
 */
@Composable
private fun ExerciseRow(
    position: Int,
    row: SummaryExerciseRow,
) {
    val name = row.name ?: stringResource(R.string.summary_exercise_unknown)
    val meta = row.metaLine()
    val spoken = row.spokenDescription(position = position, name = name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(
                horizontal = TitanTheme.spacing.card,
                vertical = TitanTheme.spacing.lg,
            ),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatCount(position),
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // Fixed rather than wrapped, so the names line up down the column whether the
            // index is one digit or two.
            modifier = Modifier.width(INDEX_COLUMN_WIDTH),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (row.name == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // The record's second appearance, after its own card. The card answers "what
                // did I beat"; this answers "which of these was it", which is the question a
                // reader has while looking at the list.
                if (row.record != null) PrBadge(type = row.record.type)
            }
            if (meta != null) {
                Text(
                    text = meta,
                    style = TitanTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `4 × 8-10 · 80 kg max`, or the half of it the exercise has.
 *
 * Both halves are optional and for different reasons. A timed hold or a distance logs no reps,
 * so there is no range to show; a bodyweight or cardio exercise carries no weight, and `0 kg`
 * is a load nobody lifted. An exercise with neither — a set of pull-ups logged as done and
 * nothing else — draws no line at all rather than a bare set count with two separators around
 * it.
 */
@Composable
@ReadOnlyComposable
private fun SummaryExerciseRow.metaLine(): String? {
    val sets = repRange()?.let { stringResource(R.string.summary_exercise_sets, setCount, it) }
    val top = topWeight?.let { stringResource(R.string.summary_exercise_top_weight, formatWeight(it)) }

    return when {
        sets != null && top != null -> stringResource(R.string.summary_exercise_meta, sets, top)
        sets != null -> sets
        else -> top
    }
}

/** `8-10`, or `10` when every set hit the same number, or null for an exercise with no reps. */
@Composable
@ReadOnlyComposable
private fun SummaryExerciseRow.repRange(): String? {
    val low = lowestReps ?: return null
    val high = highestReps ?: return null

    return if (low == high) {
        formatCount(low)
    } else {
        stringResource(R.string.summary_exercise_rep_range, low, high)
    }
}

/**
 * The row as a sentence, for the screen reader that gets no columns.
 *
 * The words the row abbreviates, spelled out: `4 × 8-10` is read as four sets of eight to ten
 * reps, and the `PR` badge names the kind of record after its letters — "PR 1RM, personal
 * record" — rather than leaving the reader with `P R` alone. That is the whole reason a row may
 * abbreviate at all.
 */
@Composable
@ReadOnlyComposable
private fun SummaryExerciseRow.spokenDescription(
    position: Int,
    name: String,
): String =
    listOfNotNull(
        stringResource(R.string.summary_exercise_description_position, position, name),
        pluralStringResource(R.plurals.summary_exercise_description_sets, setCount, setCount),
        spokenReps(),
        topWeight?.let { stringResource(R.string.summary_exercise_top_weight, formatWeight(it)) },
        record?.let { stringResource(CoreR.string.pr_description, it.type.label()) },
    ).joinToString(stringResource(CoreR.string.spoken_separator))

/** `8 to 10 reps`, or `10 reps`, or nothing for an exercise that logs none. */
@Composable
@ReadOnlyComposable
private fun SummaryExerciseRow.spokenReps(): String? {
    val low = lowestReps ?: return null
    val high = highestReps ?: return null

    return if (low == high) {
        pluralStringResource(R.plurals.summary_exercise_description_reps, low, low)
    } else {
        pluralStringResource(R.plurals.summary_exercise_description_rep_range, high, low, high)
    }
}

/** Wide enough for a two-digit index in the mono role; a session rarely runs past ten. */
private val INDEX_COLUMN_WIDTH = 16.dp
