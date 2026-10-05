package com.titan.feature.progression.quests.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.UserProgress
import com.titan.core.ui.component.BadgeTone
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.StreakFlame
import com.titan.core.ui.component.StreakShields
import com.titan.core.ui.component.TitanBadge
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.core.ui.R as CoreR

/**
 * The streak: how many weeks running the user has met their weekly goal, whether that is the
 * best it has been, and where this week stands against the goal.
 *
 * ### The flame burns while the streak does
 *
 * A live streak is drawn with [StreakFlame] beside its count — the one perpetual animation in the
 * app, and the one figure on this screen that a week can take away. A streak of zero gets
 * no flame at all rather than a dead one; that component's KDoc carries the argument.
 *
 * ### Two states, because a streak of zero is not a small streak
 *
 * A user who has never trained, and one who let a thirty-week run lapse, both read zero here — the
 * store cannot tell them apart, and neither can this. Both get an invitation to start one rather
 * than a `0` next to a flame, which is a number that reads as a failure rather than as a
 * beginning.
 *
 * A **lapsed** run reads the same way, and that is the point of [isLive]. The stored count is only
 * recomputed when a session finishes, so it stands at twelve for as long as nobody trains; drawing
 * it unasked would tell somebody who last trained in March that they are twelve weeks deep, which
 * is the one thing a streak must never do. The ViewModel asks the clock — see
 * `StreakCalculator.isLive` — and this card draws the invitation for a run that is over.
 *
 * ### "Your best yet" replaces the best, rather than sitting beside it
 *
 * `5 weeks · Best: 5 weeks` is two figures the reader has to compare to find the fact. When the
 * current run *is* the best, the card says so and drops the redundant number. That is the moment
 * worth marking on this screen, so it takes the accent badge.
 *
 * ### This week sits under the count, in both states
 *
 * A weekly goal is only something to aim at if the user can see how far through it they are, and
 * this is the screen that explains the streak. So the week in progress is drawn under the count —
 * "2 of 4 workouts this week" — whether or not there is a streak to extend: for a user with none
 * it is how one starts. Once the goal is met the line says so instead, in the accent, because
 * that is the moment the week joined the streak.
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
 * the run they are about to start from being lost to one short week. The card would also be
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
    sessionsThisWeek: Int,
    weeklyGoal: Int,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        ) {
            Eyebrow(text = stringResource(R.string.quest_log_streak_heading))

            if (progress.currentStreak == 0 || !isLive) {
                NoStreak()
            } else {
                Streak(progress = progress, isOnBest = isOnBest)
            }

            ThisWeek(sessions = sessionsThisWeek, goal = weeklyGoal)

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
        pluralStringResource(
            R.plurals.quest_log_protection_none_description,
            StreakProtection.MONTHLY_ALLOWANCE,
            StreakProtection.MONTHLY_ALLOWANCE,
        )
    } else {
        // The noun belongs to the allowance, not to what is left of it: "1 of 3 protections",
        // "0 of 1 protection".
        pluralStringResource(
            R.plurals.quest_log_protection_description,
            StreakProtection.MONTHLY_ALLOWANCE,
            remaining,
            StreakProtection.MONTHLY_ALLOWANCE,
        )
    }

    val clauses = listOf(label, spoken).joinToString(stringResource(CoreR.string.spoken_separator))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = clauses },
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
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
    val weeks = pluralStringResource(
        R.plurals.quest_log_streak_weeks,
        progress.currentStreak,
        progress.currentStreak,
    )
    val best = pluralStringResource(
        R.plurals.quest_log_streak_best,
        progress.longestStreak,
        progress.longestStreak,
    )
    val personalBest = stringResource(R.string.quest_log_streak_personal_best)
    val clauses = listOf(weeks, if (isOnBest) personalBest else best)
        .joinToString(stringResource(CoreR.string.spoken_separator))

    // A flow rather than a row: "Best: 21 weeks" beside a large count does not fit a narrow
    // screen in every language, and the best moving under the count whole reads better than it
    // breaking across two lines beside it.
    FlowRow(
        // One node: "12 weeks, best 30" is one fact, and two nodes for it is two swipes.
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = clauses },
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // The flame and the count are one item, so the flame never wraps away from its figure.
        Row(
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StreakFlame()

            Text(
                text = weeks,
                style = TitanTheme.typography.numericLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (isOnBest) {
            TitanBadge(text = personalBest, tone = BadgeTone.ACCENT)
        } else {
            Text(
                text = best,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The week in progress against the goal, or that the goal is met. */
@Composable
private fun ThisWeek(
    sessions: Int,
    goal: Int,
) {
    val met = sessions >= goal
    Text(
        text = if (met) {
            stringResource(R.string.quest_log_week_met)
        } else {
            pluralStringResource(R.plurals.quest_log_week_progress, goal, sessions, goal)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (met) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoStreak() {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs)) {
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
