package com.opengym.feature.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.QuestMetric
import com.opengym.core.domain.model.QuestPeriod
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.LevelProgressBar
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.QUEST_MARK_COMPACT_SIZE
import com.opengym.core.ui.component.QuestMark
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.format.formatVolumeValue
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.home.QuestSummary
import com.opengym.feature.home.R
import java.text.NumberFormat

/**
 * What is left to clear this week, on the screen the app opens on.
 *
 * ### Why the dashboard has this at all
 *
 * A quest is the one kind of goal that expires. It lived two taps away on the quest log, which
 * meant a lifter learned what was left this week only by going to look — and a deadline nobody
 * is reminded of is a deadline that passes. This is the reminder: the score, the two or three
 * quests closest to done, and a way to the whole board.
 *
 * ### A prompt, not the board
 *
 * Three rows at most, no cadence headings, no cleared group and no rewards. Everything left out
 * is left out because it belongs to a screen somebody has *chosen* to open. What survives is the
 * only question a dashboard should answer: is there something here I could finish today.
 *
 * ### Absent for a user with nothing on the board
 *
 * The caller draws nothing when the summary is null, which is a brand-new user — see
 * [QuestSummary]. The card is never a list of zeroes.
 */
@Composable
internal fun QuestSection(
    quests: QuestSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        SectionHeading(
            text = stringResource(R.string.home_quests_heading),
            trailing = {
                Text(
                    text = stringResource(
                        R.string.home_quests_score,
                        quests.clearedThisPeriod,
                        quests.totalThisPeriod,
                    ),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        val openBoard = stringResource(
            R.string.home_quests_open_board_description,
            quests.clearedThisPeriod,
            quests.totalThisPeriod,
        )

        OpenGymCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        ) {
            Column(
                modifier = Modifier.padding(OpenGymTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            ) {
                if (quests.isCleared) {
                    ClearedRow(period = quests.period)
                } else {
                    quests.next.forEachIndexed { index, row ->
                        QuestPreviewRow(row = row)
                        if (index != quests.next.lastIndex) {
                            HairlineDivider()
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = openBoard },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_quests_open_board),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * One quest, compact: its mark, what it asks, how far along, and a slim bar.
 *
 * The reward is deliberately absent. On the board the XP is what tells a lifter which quest is
 * worth going after; here there is nothing to choose between — these three are already the
 * nearest, and a column of prices would turn a prompt into a menu.
 */
@Composable
private fun QuestPreviewRow(
    row: QuestProgress,
    modifier: Modifier = Modifier,
) {
    val name = row.quest.label()
    val counts = stringResource(
        R.string.home_quests_progress,
        formatQuestFigure(metric = row.quest.metric, value = row.progress),
        formatQuestFigure(metric = row.quest.metric, value = row.quest.target),
    )
    val spoken = stringResource(R.string.home_quests_description, name, counts)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuestMark(metric = row.quest.metric, size = QUEST_MARK_COMPACT_SIZE)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = counts,
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LevelProgressBar(
                progress = { row.fraction },
                // The row speaks as one node; this is never read on its own.
                contentDescription = spoken,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

/** Everything cleared: the one state where the card congratulates instead of prompting. */
@Composable
private fun ClearedRow(
    period: QuestPeriod,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier.fillMaxWidth(),
        text = stringResource(
            when (period) {
                QuestPeriod.WEEKLY -> R.string.home_quests_all_clear_weekly
                QuestPeriod.MONTHLY -> R.string.home_quests_all_clear_monthly
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The quest's words, by id.
 *
 * Duplicated from `:feature:progression` and `:feature:session`, which draw the same seven. A
 * string resource belongs to one module, and the alternative — moving them to `:core:ui` — would
 * give the design-system module knowledge of the gamification catalogue, which is the worse edge.
 */
@Composable
private fun Quest.label(): String =
    when (id) {
        "weekly_sessions" -> stringResource(R.string.quest_weekly_sessions)
        "weekly_sessions_plus" -> stringResource(R.string.quest_weekly_sessions_plus)
        "weekly_record" -> stringResource(R.string.quest_weekly_record)
        "weekly_volume" -> stringResource(R.string.quest_weekly_volume)
        "monthly_sessions" -> stringResource(R.string.quest_monthly_sessions)
        "monthly_records" -> stringResource(R.string.quest_monthly_records)
        "monthly_volume" -> stringResource(R.string.quest_monthly_volume)
        else -> stringResource(R.string.quest_unknown)
    }

/**
 * One of a quest's two figures, in the unit its metric is read in rather than stored in.
 *
 * Sessions and records are counts, grouped in the *formatting* locale rather than with a literal
 * separator — an ungrouped `20000` is a number the eye has to count digits on. A volume figure is
 * milligrams on the way in, since that is what [QuestMetric.VOLUME_MILLIGRAMS] stores, and
 * printing it raw would put `20,000,000,000` under a quest called "Move 20 tonnes";
 * [formatVolumeValue] puts it back in the user's own unit.
 *
 * The board draws its figures through the same two rules — see `QuestLogLabels.formatQuestFigure`.
 * Duplicated rather than shared: the two modules each own their strings, and a helper in
 * `:core:ui` would be a design system that knows what a quest is.
 */
@Composable
private fun formatQuestFigure(
    metric: QuestMetric,
    value: Long,
): String =
    when (metric) {
        QuestMetric.SESSIONS_FINISHED, QuestMetric.RECORDS_BROKEN ->
            NumberFormat.getIntegerInstance(Locale.current.platformLocale).format(value)

        QuestMetric.VOLUME_MILLIGRAMS -> formatVolumeValue(value)
    }
