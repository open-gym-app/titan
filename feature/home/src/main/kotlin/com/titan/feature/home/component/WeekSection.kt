package com.titan.feature.home.component

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.WeekStrip
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.home.R
import com.titan.feature.home.WeekState

/**
 * The week so far: seven days, and what they add up to.
 *
 * ```text
 * THIS WEEK ────────────────────────────────
 * ┌─────────────────────────────────────────┐
 * │  M   T   W   T   F   S   S              │
 * │  ▓   ░   ▓   ░   ░   ▓   ░              │
 * │ ─────────────────────────────────────── │
 * │ 3 sessions                12-week streak│
 * └─────────────────────────────────────────┘
 * ```
 *
 * The count under the rule is the week's, not the month's and not all time: it is the figure
 * that answers "am I keeping this up" at a glance.
 *
 * ### The streak is the same question over a longer window
 *
 * It sits on the same line for that reason, and it takes the accent because it is the one
 * figure on this card that is a *result* rather than a fact — the distinction `StatTile` draws
 * for the same treatment. A streak that has lapsed is not drawn at all rather than drawn as
 * zero: the ViewModel is what decides whether the stored figure is still running, and null here
 * means it is not — see [WeekState.streak].
 *
 * **The whole card opens the Sessions tab.** A dashboard card carrying a figure is a thing
 * users tap, and seven cells and a count that answer nothing back is a control that dead-ends;
 * what is behind it is the list those sessions are in.
 *
 * @param onClick the card was tapped. Not optional, for the reason a template card's click is
 *   not: a card in a dashboard that does nothing is a control the user will try twice.
 */
@Composable
fun WeekSection(
    week: WeekState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        SectionHeading(text = stringResource(R.string.home_week_heading))

        TitanCard {
            Column(
                // The click sits *inside* the card, as `TemplateRow`'s does: on the modifier
                // handed to [TitanCard] it would be applied outside the `Surface`'s own clip
                // and the ripple would be drawn square over a rounded card. Before the padding,
                // so the whole card is the target and not the column of content in it.
                modifier = Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.home_week_open),
                        onClick = onClick,
                    )
                    .padding(TitanTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            ) {
                WeekStrip(days = week.days)
                HairlineDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.home_week_sessions,
                            week.sessionCount,
                            week.sessionCount,
                        ),
                        style = TitanTheme.typography.numeric,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    week.streak?.let { streak ->
                        Text(
                            text = pluralStringResource(R.plurals.home_week_streak, streak, streak),
                            style = TitanTheme.typography.numeric,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
