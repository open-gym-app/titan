package com.opengym.feature.progression.quests.component

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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.opengym.core.domain.model.UserProgress
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.StreakFlame
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R

/**
 * The streak: how many days running, and whether that is the best it has been.
 *
 * ### The flame burns while the streak does
 *
 * A live streak is drawn with [StreakFlame] beside its count — the one perpetual animation in the
 * app, and the one figure on this screen that is true only until tomorrow. A streak of zero gets
 * no flame at all rather than a dead one; that component's KDoc carries the argument.
 *
 * ### Two states, because a streak of zero is not a small streak
 *
 * A user who has never trained, and one who let a thirty-day run lapse, both read zero here — the
 * store cannot tell them apart, and neither can this. Both get an invitation to start one rather
 * than a `0` next to a flame, which is a number that reads as a failure rather than as a
 * beginning.
 *
 * ### "Your best yet" replaces the best, rather than sitting beside it
 *
 * `5 days · Best: 5 days` is two figures the reader has to compare to find the fact. When the
 * current run *is* the best, the card says so and drops the redundant number. That is the moment
 * worth marking on this screen, so it takes the accent badge.
 *
 * ### Streak protection is not here
 *
 * #104 asks for streak-protection status alongside these figures. It comes from #M5-12, which has
 * not landed, and there is no partial version of it worth drawing: a card that showed a
 * protection state the app cannot actually apply would be telling the user their streak is safe
 * when nothing is holding it. The row is added with the feature.
 */
@Composable
internal fun StreakCard(
    progress: UserProgress,
    isOnBest: Boolean,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            Eyebrow(text = stringResource(R.string.quest_log_streak_heading))

            if (progress.currentStreak == 0) {
                NoStreak()
            } else {
                Streak(progress = progress, isOnBest = isOnBest)
            }
        }
    }
}

@Composable
private fun Streak(
    progress: UserProgress,
    isOnBest: Boolean,
) {
    val days = pluralStringResource(
        R.plurals.quest_log_streak_days,
        progress.currentStreak,
        progress.currentStreak,
    )
    val best = pluralStringResource(
        R.plurals.quest_log_streak_best,
        progress.longestStreak,
        progress.longestStreak,
    )
    val personalBest = stringResource(R.string.quest_log_streak_personal_best)

    Row(
        // One node: "12 days, best 30" is one fact, and two nodes for it is two swipes.
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = if (isOnBest) "$days, $personalBest" else "$days, $best"
            },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreakFlame()

        Text(
            text = days,
            style = OpenGymTheme.typography.numericLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (isOnBest) {
            OpenGymBadge(text = personalBest, tone = BadgeTone.ACCENT)
        } else {
            Text(
                text = best,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoStreak() {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.quest_log_streak_none),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.quest_log_streak_none_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
