package com.opengym.feature.progression.quests.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.opengym.core.domain.model.StreakProtection
import com.opengym.core.domain.model.UserProgress
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.StreakFlame
import com.opengym.core.ui.component.StreakShields
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
 * A **lapsed** run reads the same way, and that is the point of [isLive]. The stored count is only
 * recomputed when a session finishes, so it stands at twelve for as long as nobody trains; drawing
 * it unasked would tell somebody who last trained in March that they are twelve days deep, which
 * is the one thing a streak must never do. The ViewModel asks the clock — see
 * `StreakCalculator.isLive` — and this card draws the invitation for a run that is over.
 *
 * ### "Your best yet" replaces the best, rather than sitting beside it
 *
 * `5 days · Best: 5 days` is two figures the reader has to compare to find the fact. When the
 * current run *is* the best, the card says so and drops the redundant number. That is the moment
 * worth marking on this screen, so it takes the accent badge.
 *
 * ### Streak protection sits under the count, not beside it
 *
 * The allowance (#106) is drawn on this card because it is a fact *about* the streak above it —
 * what is standing between that number and a bad week — and nowhere else on the screen would say
 * that. It is below a rule rather than in the same row because it is a different kind of fact: the
 * streak is what the user did, the allowance is what the app will do for them, and the two read as
 * one confused figure when set side by side.
 *
 * Drawn in **both** streak states, unlike everything else here. A user with no streak still has an
 * allowance, and it is the state where knowing about it matters most — a protection is what keeps
 * the run they are about to start from being lost to one missed day. The card would also be
 * claiming the allowance did not exist by omitting it, which is worse than saying nothing.
 *
 * The marks come from [StreakShields] in `:core:ui`, which the just-finished recap draws too;
 * the count in words sits beside them and the marks stay silent, per that component's contract.
 */
@Composable
internal fun StreakCard(
    progress: UserProgress,
    isLive: Boolean,
    isOnBest: Boolean,
    protection: StreakProtection,
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

            if (progress.currentStreak == 0 || !isLive) {
                NoStreak()
            } else {
                Streak(progress = progress, isOnBest = isOnBest)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Protection(protection = protection)
        }
    }
}

/**
 * The allowance: what it is called, how many are left, and a mark for each.
 *
 * The marks carry no semantics of their own — [StreakShields] is passed null — because the row is
 * one fact and is announced as one: the label, then the count in words. A reader that heard the
 * marks *and* the line would hear the same thing twice, which is the case that component's
 * contract calls out.
 */
@Composable
private fun Protection(protection: StreakProtection) {
    val remaining = protection.remaining.coerceIn(0, StreakProtection.MONTHLY_ALLOWANCE)
    val label = stringResource(R.string.quest_log_protection_label)
    val left = if (remaining == 0) {
        stringResource(R.string.quest_log_protection_none)
    } else {
        pluralStringResource(R.plurals.quest_log_protection_left, remaining, remaining)
    }
    val spoken = if (remaining == 0) {
        stringResource(R.string.quest_log_protection_none_description, StreakProtection.MONTHLY_ALLOWANCE)
    } else {
        stringResource(
            R.string.quest_log_protection_description,
            remaining,
            StreakProtection.MONTHLY_ALLOWANCE,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$label, $spoken" },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StreakShields(
            remaining = remaining,
            allowance = StreakProtection.MONTHLY_ALLOWANCE,
            contentDescription = null,
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = left,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
