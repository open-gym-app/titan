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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.LevelProgressBar
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.quests.formatCount
import com.opengym.feature.progression.quests.label

/**
 * One quest: what it asks, how far along, and what it pays.
 *
 * ### A completed quest is struck through, not removed
 *
 * #104 asks for completed quests to be visually distinct, and the distinction is deliberately
 * *quiet*: struck through, a `Done` badge, and a full bar in the accent. A quest cleared this week
 * is a thing the user did, and dropping it off the board would make a good week look like an empty
 * one by Sunday. It keeps its place in catalogue order rather than sorting to the bottom, so the
 * board does not reshuffle under the user as they clear it.
 *
 * ### The bar reads the completion, not the figures
 *
 * [QuestProgress.fraction] is full for a banked quest whatever the counts say — see that class,
 * which carries why: a user who deletes a session they were already paid for keeps the quest, and
 * a bar that dropped back would contradict the badge beside it. The counts still show the real
 * figures, because those are true.
 *
 * ### One node per row
 *
 * "Train three times, 2 / 3, worth 60 XP" as one sentence. A reader moving down a board of seven
 * quests should hear each as one statement, not four fragments to assemble — the same treatment
 * `PersonalRecordRow` and the summary's stat tiles get.
 */
@Composable
internal fun QuestRow(
    row: QuestProgress,
    modifier: Modifier = Modifier,
) {
    val name = row.quest.label()
    val counts = stringResource(
        R.string.quest_log_quest_progress,
        formatCount(row.progress.toLong()),
        formatCount(row.quest.target.toLong()),
    )
    val spoken = if (row.isComplete) {
        stringResource(R.string.quest_log_quest_description_done, name, row.quest.xpReward)
    } else {
        stringResource(R.string.quest_log_quest_description, name, counts, row.quest.xpReward)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = OpenGymTheme.spacing.lg)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.isComplete) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (row.isComplete) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.isComplete) {
                OpenGymBadge(text = stringResource(R.string.quest_log_quest_done), tone = BadgeTone.ACCENT)
            } else {
                Text(
                    text = stringResource(R.string.quest_log_quest_reward, row.quest.xpReward),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        LevelProgressBar(
            // A lambda, as the component asks: the fraction is read at draw time so a board that
            // moves while it is on screen does not recompose the whole row to move a bar.
            progress = { row.fraction },
            contentDescription = spoken,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = counts,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.isComplete) {
                // The reward stays visible on a cleared quest, in the muted role: what it *paid*
                // is the reason the row is worth keeping on the board.
                Text(
                    text = stringResource(R.string.quest_log_quest_reward, row.quest.xpReward),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
