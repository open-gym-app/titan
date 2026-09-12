package com.opengym.feature.home.component

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
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.withValue
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.PersonalRecordRow
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.formattedValue
import com.opengym.core.ui.component.label
import com.opengym.core.ui.format.formatDayAndMonth
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.home.R
import com.opengym.core.ui.R as CoreR

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
    val kind = record.record.type.label()
    val value = record.record.formattedValue()
    val date = formatDayAndMonth(record.record.achievedAt)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        SectionHeading(text = stringResource(R.string.home_record_heading))

        OpenGymCard {
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
                    record.improvementLabel()?.let { improvement ->
                        Text(
                            text = improvement,
                            style = OpenGymTheme.typography.numericSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                // Inside the card, as the week card's click is: on the modifier handed to
                // [OpenGymCard] the ripple would be drawn square over a rounded card. Before
                // the padding, so the whole card is the target.
                modifier = Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.home_record_open),
                        onClick = onClick,
                    )
                    .padding(OpenGymTheme.spacing.card),
            )
        }
    }
}

/**
 * How much this record beat the one before it — `+7 kg` — or null when it beat nothing.
 *
 * Formatted through the record's own formatter with the improvement substituted for its value,
 * exactly as #103's row does it: a second `when` over the kind at the call site is how two
 * screens end up disagreeing about what a kind is measured in.
 */
@Composable
private fun PersonalRecordEntry.improvementLabel(): String? {
    val improvement = improvement ?: return null
    val asRecord = record.withValue(improvement) ?: return null
    return asRecord.formattedValue()?.let { stringResource(R.string.home_record_improvement, it) }
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
    return listOfNotNull(base, badge, record.improvementLabel())
        .joinToString(stringResource(CoreR.string.spoken_separator))
}
