package com.titan.feature.progression.records.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.PersonalRecordRow
import com.titan.core.ui.component.formattedImprovement
import com.titan.core.ui.component.formattedValue
import com.titan.core.ui.component.label
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.core.ui.R as CoreR

/**
 * One record: what it was set on, what kind it is, what it reads, and what it beat.
 *
 * The list's form of `:core:ui`'s [PersonalRecordRow], which is the same three pieces the
 * exercise's own bests are drawn from. What this screen adds to them is what a list *across*
 * exercises needs and a card about one does not: the exercise's name, the badge marking the last
 * session's records, the improvement, and somewhere to tap.
 *
 * ### The improvement is drawn only when there was one
 *
 * A first record of its kind improved on nothing, and `+0` under it would credit the user with
 * beating a record they never held — see [PersonalRecordEntry.improvement], which is null for
 * exactly that case rather than zero. How it is written — `+12 kg`, or `−0:17 /km` for a pace,
 * which beats by going down — is `formattedImprovement`'s, shared with Home's card.
 *
 * ### The whole row is one node to a screen reader
 *
 * Five pieces of text that are read as one sentence: a reader moving through a list of two
 * hundred records should hear "Bench press, heaviest, 92 kilograms, 21 April, up 12" once, not
 * five fragments they have to assemble. [PersonalRecordRow] clears and replaces the row's
 * semantics for that reason, which is the same call `PrValue` and the summary's stat tiles make.
 */
@Composable
internal fun PersonalRecordEntryRow(
    entry: PersonalRecordEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = entry.exerciseName ?: stringResource(R.string.records_unknown_exercise)
    val kind = entry.record.label()
    val value = entry.record.formattedValue()
    val date = formatDayAndMonth(entry.record.achievedAt)

    PersonalRecordRow(
        kind = kind,
        date = date,
        contentDescription = spokenRow(
            name = name,
            kind = kind,
            value = value.orEmpty(),
            date = date,
            entry = entry,
        ),
        value = value,
        name = name,
        badge = {
            // The badge marks the last session's records, which is the one thing on this
            // screen that is *news* rather than history.
            if (entry.isRecent) {
                Eyebrow(
                    text = stringResource(R.string.records_new_badge),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        trailing = {
            entry.formattedImprovement()?.let { improvement ->
                Text(
                    text = improvement,
                    style = TitanTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(
                horizontal = TitanTheme.spacing.screen,
                vertical = TitanTheme.spacing.lg,
            ),
    )
}

/**
 * One sentence for the whole row. See the note on [PersonalRecordEntryRow].
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
    return listOfNotNull(base, badge, entry.formattedImprovement())
        .joinToString(stringResource(CoreR.string.spoken_separator))
}
