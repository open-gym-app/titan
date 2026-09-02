package com.opengym.feature.progression.records.component

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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.withValue
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.formattedValue
import com.opengym.core.ui.component.label
import com.opengym.core.ui.format.formatDayAndMonth
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R

/**
 * One record: what it was set on, what kind it is, what it reads, and what it beat.
 *
 * ### The improvement is drawn only when there was one
 *
 * A first record of its kind improved on nothing, and `+0` under it would credit the user with
 * beating a record they never held — see [PersonalRecordEntry.improvement], which is null for
 * exactly that case rather than zero.
 *
 * ### The whole row is one node to a screen reader
 *
 * Five pieces of text that are read as one sentence: a reader moving through a list of two
 * hundred records should hear "Bench press, heaviest, 92 kilograms, 21 April, up 12" once, not
 * five fragments they have to assemble. The semantics are cleared and replaced for that reason,
 * which is the same call `PrValue` and the summary's stat tiles make.
 */
@Composable
internal fun PersonalRecordRow(
    entry: PersonalRecordEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = entry.exerciseName ?: stringResource(R.string.records_unknown_exercise)
    val kind = entry.record.type.label()
    val value = entry.record.formattedValue().orEmpty()
    val date = formatDayAndMonth(entry.record.achievedAt)
    val spoken = spokenRow(name = name, kind = kind, value = value, date = date, entry = entry)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = OpenGymTheme.spacing.screen,
                vertical = OpenGymTheme.spacing.lg,
            )
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow(text = kind)
                // The badge marks the last session's records, which is the one thing on this
                // screen that is *news* rather than history.
                if (entry.isRecent) {
                    Eyebrow(
                        text = stringResource(R.string.records_new_badge),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = date,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = OpenGymTheme.typography.numeric,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            entry.improvementLabel()?.let { improvement ->
                Text(
                    text = improvement,
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The improvement as text — `+12 kg`, `+2 reps` — or null when this record beat nothing.
 *
 * Formatted through the same helper the record's own figure uses, so the two agree on units and
 * decimals. The record is copied with the improvement substituted for its value rather than
 * formatted by a second `when` over [com.opengym.core.domain.model.PrType]: a seventh kind then
 * has one place to be added here instead of two that can disagree about what a kind is measured
 * in.
 */
@Composable
private fun PersonalRecordEntry.improvementLabel(): String? {
    val improvement = improvement ?: return null
    val asRecord = record.withValue(improvement) ?: return null
    return asRecord.formattedValue()?.let { stringResource(R.string.records_improvement, it) }
}

/**
 * One sentence for the whole row. See the note on [PersonalRecordRow].
 *
 * The `NEW` badge is part of it, because clearing the row's semantics takes every child node's
 * text with it: a badge left out here is a badge that exists only for people who can see it, and
 * it marks the one thing on this screen that is news rather than history.
 */
@Composable
private fun spokenRow(
    name: String,
    kind: String,
    value: String,
    date: String,
    entry: PersonalRecordEntry,
): String {
    val base = stringResource(R.string.records_row_description, name, kind, value, date)
    val badge = if (entry.isRecent) stringResource(R.string.records_new_badge) else null
    return listOfNotNull(base, badge, entry.improvementLabel()).joinToString(separator = ", ")
}
