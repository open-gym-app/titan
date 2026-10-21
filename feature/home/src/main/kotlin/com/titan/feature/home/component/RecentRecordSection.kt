package com.titan.feature.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.PersonalRecordRow
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.formattedImprovement
import com.titan.core.ui.component.formattedValue
import com.titan.core.ui.component.label
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.home.R
import com.titan.core.ui.R as CoreR

/**
 * The last thing the user beat: `Deadlift · heaviest · 142 kg`, and what it beat.
 *
 * ```text
 * RECENT PR ────────────────────────────────
 * ┌──────────────────────────────────────────┐
 * │ HEAVIEST  NEW                    142 kg  │
 * │ Deadlift                          +7 kg  │
 * │ 21 Apr                                   │
 * └──────────────────────────────────────────┘
 * ```
 *
 * ### The same row the records list draws
 *
 * `:core:ui`'s [PersonalRecordRow], with the same three pieces in the same three type roles as
 * #103's list and the exercise screen's bests. A card that laid a record out its own way would
 * be the third design for one thing, and a user tapping through from here to the list would
 * watch the row they tapped turn into something else.
 *
 * ### One record, because a dashboard answers at a glance
 *
 * The newest one, and the way to the rest is the card itself. A list of five here would be the
 * records screen with four rows missing.
 *
 * @param onClick the card was tapped: the user wants the records behind it. Not optional, for
 *   the reason the week card's is not — a dashboard card carrying a figure is a thing users tap,
 *   and one that dead-ends is a control they will try twice.
 */
@Composable
fun RecentRecordSection(
    record: PersonalRecordEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = record.exerciseName ?: stringResource(R.string.home_record_unknown_exercise)
    val kind = record.record.label()
    val value = record.record.formattedValue()
    val date = formatDayAndMonth(record.record.achievedAt)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        SectionHeading(text = stringResource(R.string.home_record_heading))

        TitanCard {
            PersonalRecordRow(
                kind = kind,
                date = date,
                contentDescription = spokenRecord(
                    name = name,
                    kind = kind,
                    value = value.orEmpty(),
                    date = date,
                    record = record,
                ),
                value = value,
                name = name,
                badge = {
                    // The one thing on this card that is *news* rather than history: a record
                    // set in the workout the user has just finished, as opposed to one from
                    // three weeks ago that is still their best.
                    if (record.isRecent) {
                        Eyebrow(
                            text = stringResource(R.string.home_record_new_badge),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                trailing = {
                    record.formattedImprovement()?.let { improvement ->
                        Text(
                            text = improvement,
                            style = TitanTheme.typography.numericSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                // Inside the card, as the week card's click is: on the modifier handed to
                // [TitanCard] the ripple would be drawn square over a rounded card. Before
                // the padding, so the whole card is the target.
                modifier = Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.home_record_open),
                        onClick = onClick,
                    )
                    .padding(TitanTheme.spacing.card),
            )
        }
    }
}

/**
 * The card as one sentence. [PersonalRecordRow] clears its subtree's semantics, so anything a
 * slot draws — the badge, the improvement — exists only for people who can see it unless it is
 * said here.
 */
@Composable
private fun spokenRecord(
    name: String,
    kind: String,
    value: String,
    date: String,
    record: PersonalRecordEntry,
): String {
    val base = stringResource(R.string.home_record_description, name, kind, value, date)
    val badge = if (record.isRecent) stringResource(R.string.home_record_new_badge) else null
    return listOfNotNull(base, badge, record.formattedImprovement())
        .joinToString(stringResource(CoreR.string.spoken_separator))
}
