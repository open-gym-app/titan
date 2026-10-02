package com.titan.feature.session.summary.component

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
import androidx.compose.ui.text.style.TextOverflow
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PrBadge
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.formattedValue
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R
import com.titan.feature.session.summary.SummaryRecord

/**
 * What the session beat — the one thing on this screen that is a *result* rather than a fact.
 *
 * ### Why it is a bordered card and not a filled one
 *
 * The wireframe fills this card with the accent and sets it in white. `TitanCard` will not
 * do that, on purpose: its own documentation is that an accented card takes the border and
 * never the fill, because a block of accent is what the primary button on the same screen is
 * made of, and two of them means neither reads as the thing to look at. So the record gets the
 * accent three times over — the border, the `PR` badge, the figure — against a screen where
 * everything else is a grey rule, and DONE keeps the only filled accent on the page.
 *
 * There is never a second accented card here, which is the rule that keeps this one working.
 *
 * The caller draws nothing at all when there are no records; a "no records" card is a section
 * telling the user they failed to achieve something, on the screen that exists to show them
 * what they did.
 */
@Composable
internal fun NewRecordsCard(
    records: List<SummaryRecord>,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth(), accented = true) {
        records.forEachIndexed { index, record ->
            if (index > 0) HairlineDivider()
            RecordRow(record)
        }
    }
}

/**
 * `PR · 1RM` over the exercise, with the record's own figure beside it.
 *
 * The badge names the *kind* of record and the figure is the record, which is the pair
 * `PrBadge` and `PrValue` were built as. `PrValue` itself is not used because it stacks its
 * own `PR` eyebrow over the number, and next to a badge already saying `PR` that is the same
 * word twice on one row.
 */
@Composable
private fun RecordRow(record: SummaryRecord) {
    // Null for a record whose own figure is missing — the type says which of the five columns
    // carries the value, and a row where those disagree is one nothing can honestly draw.
    val value = record.record.formattedValue()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(TitanTheme.spacing.card),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
        ) {
            PrBadge(type = record.record.type)
            Text(
                text = record.exerciseName ?: stringResource(R.string.summary_record_unknown),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (value != null) {
            Text(
                text = value,
                style = TitanTheme.typography.numeric,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}
