package com.titan.feature.progression.body.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.SwipeToDelete
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatLength
import com.titan.core.ui.format.formatPercentTenths
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.format.formatWeight
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.body.BodySite

/**
 * One entry in the history: the day it was taken, what it recorded, and the two ways to change
 * it.
 *
 * ### Tap to correct, swipe to remove
 *
 * The pair the app already uses for a logged set — see `LoggedSetTable`. There is no editor
 * button and no overflow menu, because a row that carries a control per action is a row of
 * controls; the gesture is the affordance, and TalkBack is given both as named actions rather
 * than as a swipe it cannot perform.
 *
 * ### The summary is what the entry actually holds
 *
 * Every measurement is independently optional, so a row is built from the figures that are
 * there rather than from a fixed set of columns with dashes in the gaps: a weigh-in reads
 * `78.4 kg`, and a full tape session reads all six. The order is fixed — weight, the four
 * parts, body fat — so two rows are read down a column rather than matched up by unit.
 *
 * Lean mass is deliberately absent. It is derived from two of the figures already on the line,
 * so drawing it would be the same fact stated twice on a row that has to fit a phone.
 *
 * @param onEdit open the form on this entry.
 * @param onDelete remove it. Immediate, with no confirmation — a dialog on every swipe is a tax
 *   on the user who mistyped one — and undoable instead: the screen offers the entry back, which
 *   is what `SwipeToDelete`'s short drag leans on to keep a slip from costing a measurement.
 */
@Composable
fun MeasurementHistoryRow(
    measurement: BodyMeasurement,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = formatSessionDate(measurement.createdAt)
    val summary = measurement.summary()
    val editLabel = stringResource(R.string.body_history_edit, date)

    SwipeToDelete(
        onDelete = onDelete,
        actionLabel = stringResource(R.string.body_history_delete, date),
        modifier = modifier,
    ) {
        TitanCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
            Row(
                // Inside the card and before the padding, the way every other tappable card in
                // the app builds its click: on the outer modifier it would ripple square over a
                // rounded surface, and after the padding it would leave a dead border.
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = editLabel, onClick = onEdit)
                    // `mergeDescendants` rather than a hand-written description, the choice the
                    // hub's explore rows document: the date and the summary are already the
                    // sentence a reader wants, in the order they are drawn, and the click stays
                    // on the node that carries them. `clearAndSetSemantics` here would wipe the
                    // click along with the text — the mistake `TrainingCalendar`'s day cell
                    // documents. What the tap *does* is the click's own label, since a row that
                    // announces a reading gives no hint that touching it opens a form.
                    .semantics(mergeDescendants = true) { }
                    .padding(
                        horizontal = TitanTheme.spacing.xl,
                        vertical = TitanTheme.spacing.lg,
                    ),
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
                ) {
                    Text(
                        text = date,
                        style = TitanTheme.typography.numericSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * What this entry recorded, as one line: `78.4 kg · Chest 102 cm · 14.2%`.
 *
 * The circumferences carry their part's name and the other two do not, because a weight and a
 * percentage are unambiguous from their units and nine figures all reading `cm` are not.
 */
@Composable
private fun BodyMeasurement.summary(): String {
    val separator = stringResource(R.string.body_history_separator)
    val figures = buildList {
        weight?.let { add(formatWeight(it)) }
        BodySite.entries.forEach { site -> site.of(this@summary)?.let { add(part(site.label, it)) } }
        bodyFat?.let { add(formatPercentTenths(it)) }
    }

    // An entry recording nothing is refused by `BodyMeasurementValidator`, so this is the row
    // for a corrupt one rather than for anything the app can write.
    return figures.takeIf { it.isNotEmpty() }?.joinToString(separator)
        ?: stringResource(R.string.body_history_empty_entry)
}

/** `Chest 102 cm` — the part's name, then its reading. */
@Composable
private fun part(
    @StringRes labelRes: Int,
    millimetres: Int,
): String = stringResource(R.string.body_history_part, stringResource(labelRes), formatLength(millimetres))

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Measurement history row", showBackground = true, widthDp = 360)
@Composable
fun MeasurementHistoryRowPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            MeasurementHistoryRow(
                measurement = BodyMeasurement(
                    id = 1,
                    weight = 78_400_000L,
                    chest = 1_020,
                    waist = 820,
                    bodyFat = 142,
                    createdAt = PREVIEW_INSTANT,
                    updatedAt = PREVIEW_INSTANT,
                ),
                onEdit = {},
                onDelete = {},
            )
            MeasurementHistoryRow(
                measurement = BodyMeasurement(
                    id = 2,
                    weight = 79_100_000L,
                    createdAt = PREVIEW_INSTANT,
                    updatedAt = PREVIEW_INSTANT,
                ),
                onEdit = {},
                onDelete = {},
            )
        }
    }
}

/** A fixed instant, so the preview's dates do not move with the wall clock. */
private const val PREVIEW_INSTANT = 1_777_000_000_000L
