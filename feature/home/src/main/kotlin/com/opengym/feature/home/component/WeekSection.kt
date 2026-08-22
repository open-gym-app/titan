package com.opengym.feature.home.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.WeekStrip
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.home.R
import com.opengym.feature.home.WeekState

/**
 * The week so far: seven days, and what they add up to.
 *
 * ```text
 * THIS WEEK ────────────────────────────────
 * ┌─────────────────────────────────────────┐
 * │  M   T   W   T   F   S   S              │
 * │  ▓   ░   ▓   ░   ░   ▓   ░              │
 * │ ─────────────────────────────────────── │
 * │ 3 sessions                              │
 * └─────────────────────────────────────────┘
 * ```
 *
 * The count under the rule is the week's, not the month's and not all time: it is the figure
 * that answers "am I keeping this up" at a glance, and the only one on this screen that M4 can
 * answer honestly. The streak the wireframe draws beside it needs #34's engine and arrives with
 * #110 — see [WeekState].
 */
@Composable
fun WeekSection(
    week: WeekState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        SectionHeading(text = stringResource(R.string.home_week_heading))

        OpenGymCard {
            Column(
                modifier = Modifier.padding(OpenGymTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
            ) {
                WeekStrip(days = week.days)
                HairlineDivider()
                Text(
                    text = pluralStringResource(
                        R.plurals.home_week_sessions,
                        week.sessionCount,
                        week.sessionCount,
                    ),
                    style = OpenGymTheme.typography.numeric,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
