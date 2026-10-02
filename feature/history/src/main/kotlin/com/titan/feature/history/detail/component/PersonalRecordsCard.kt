package com.titan.feature.history.detail.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PersonalRecordRow
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.formattedValue
import com.titan.core.ui.component.label
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.history.R

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
 * app gets. The row itself is `:core:ui`'s [PersonalRecordRow], shared with the records list, so
 * a best read here and the same best read there are the same three type roles. No name on these
 * rows: the screen is already about one exercise, and repeating it down the card says nothing.
 */
@Composable
internal fun PersonalRecordsCard(
    records: List<PersonalRecord>,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth()) {
        records.forEachIndexed { index, record ->
            if (index > 0) HairlineDivider()
            RecordRow(record = record)
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

    PersonalRecordRow(
        kind = kind,
        date = date,
        // The value as the row draws it, so a record with no figure of its own is a sentence
        // with a gap in it rather than one quoting a number nothing rendered.
        contentDescription = stringResource(
            R.string.detail_record_description,
            kind,
            value.orEmpty(),
            date,
        ),
        value = value,
        // Inset on the row rather than on a column around them, so the dividers between rows
        // stay the full-bleed rules `HairlineDivider` is for.
        modifier = modifier.padding(TitanTheme.spacing.card),
    )
}
