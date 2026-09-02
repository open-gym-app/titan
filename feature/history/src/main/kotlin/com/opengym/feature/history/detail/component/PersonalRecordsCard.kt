package com.opengym.feature.history.detail.component

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
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.formattedValue
import com.opengym.core.ui.component.label
import com.opengym.core.ui.format.formatDayAndMonth
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R

/**
 * This exercise's bests, one row per kind.
 *
 * ### One per kind, not one per record
 *
 * The store holds every record ever set — three heaviest rows as a lift went 80, 86, 92 — and
 * listing all of them would make this a log rather than a set of bests. The caller passes the
 * newest of each kind, which is that kind's best; see `bestByType`, which explains why that
 * follows from how the engine writes rather than from a comparison made here.
 *
 * ### Absent, not empty
 *
 * The caller draws nothing at all for an exercise with no records. A card reading "no records
 * yet" on a movement the user has never loaded is the screen telling them what they have failed
 * to do, which is the same call `NewRecordsCard` makes on the session recap.
 *
 * ### One node per row
 *
 * Kind, figure and date are read as one sentence — "Heaviest, 92 kg, 21 April" — rather than as
 * three fragments a reader has to assemble, which is the treatment every other record row in the
 * app gets.
 */
@Composable
internal fun PersonalRecordsCard(
    records: List<PersonalRecord>,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OpenGymTheme.spacing.card)) {
            records.forEachIndexed { index, record ->
                if (index > 0) HairlineDivider()
                RecordRow(record = record)
            }
        }
    }
}

@Composable
private fun RecordRow(
    record: PersonalRecord,
    modifier: Modifier = Modifier,
) {
    val kind = record.type.label()
    val value = record.formattedValue()
    val date = formatDayAndMonth(record.achievedAt)
    val spoken = stringResource(
        R.string.detail_record_description,
        kind,
        value.orEmpty(),
        date,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = OpenGymTheme.spacing.md)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Eyebrow(text = kind)
            Text(
                text = date,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The figure in the accent, as every record in this app is drawn — see `PrValue`, whose
        // treatment this matches so a best here and the same best on a library row read alike.
        Text(
            text = value.orEmpty(),
            style = OpenGymTheme.typography.numeric,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}
