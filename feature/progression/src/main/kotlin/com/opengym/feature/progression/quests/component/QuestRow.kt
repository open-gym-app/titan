package com.opengym.feature.progression.quests.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.ui.component.LevelProgressBar
import com.opengym.core.ui.component.QuestMark
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.LocalReducedMotion
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.core.ui.theme.PillShape
import com.opengym.feature.progression.R
import com.opengym.feature.progression.quests.formatCount
import com.opengym.feature.progression.quests.label

/**
 * One quest still to clear: its mark, what it asks, how far along, and what it pays.
 *
 * ### This row is only ever drawn for a live quest
 *
 * Cleared quests are a separate group with their own, much quieter row — see [ClearedQuestRow].
 * That split replaced an earlier design where a completed quest stayed in place, struck through:
 * it kept catalogue order stable, but it also meant a user with five of seven cleared had to read
 * past five finished lines to find the two still open, which is the board failing at the one
 * question it exists to answer. The board now leads with what is left to do.
 *
 * ### The bar reads the completion, not the figures
 *
 * [QuestProgress.fraction] is full for a banked quest whatever the counts say — see that class,
 * which carries why. A live row is never full, so what the bar shows here is always the real
 * progress; the distinction still matters, because it is what keeps this row and the cleared
 * one drawing the same value.
 *
 * ### One node per row
 *
 * "Train three times, 2 / 3, worth 60 XP" as one sentence. A reader moving down a board should
 * hear each row as one statement, not four fragments to assemble — the same treatment
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
    val spoken = stringResource(R.string.quest_log_quest_description, name, counts, row.quest.xpReward)

    // The bar grows into place on first composition rather than appearing filled. A board that
    // animates is the difference between a list of figures and something that feels alive —
    // and it lands immediately under reduced motion, on the terms every other animation in this
    // app is under. See [LocalReducedMotion].
    val reducedMotion = LocalReducedMotion.current
    val fraction by animateFloatAsState(
        targetValue = row.fraction,
        animationSpec = questBarSpec(reducedMotion),
        label = "quest progress",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = OpenGymTheme.spacing.md)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        QuestMark(metric = row.quest.metric)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            // The name gets the width, and the price sits opposite it. An earlier version put
            // the counts up here too and the three of them fought: `Move 20 tonnes` wrapped to
            // two lines on a 360dp screen and `Move 80 tonnes` was ellipsised outright, which
            // is a board that cannot say what it is asking for. The counts moved under the bar,
            // where they are the caption to the thing they are a proportion of.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RewardPill(xp = row.quest.xpReward)
            }

            LevelProgressBar(
                // A lambda, as the component asks: the animation repaints the bar without
                // recomposing the row around it.
                progress = { fraction },
                // The row above has already cleared its subtree's semantics and spoken for
                // all of it, so this is never read out; it is passed rather than blanked so a
                // future caller that draws the bar on its own still has a sentence to give.
                contentDescription = spoken,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Text(
                text = counts,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A quest already banked this period: the same mark gone neutral, the name, and what it paid.
 *
 * No bar and no counts. Both would be answering a question that is closed — the bar would be
 * full on every row of the group, and "5 / 3" invites the reader to work out whether something
 * has gone wrong. What is worth keeping is the name and the reward, because together they are
 * the record of a good week.
 */
@Composable
internal fun ClearedQuestRow(
    row: QuestProgress,
    modifier: Modifier = Modifier,
) {
    val name = row.quest.label()
    val spoken = stringResource(R.string.quest_log_quest_description_done, name, row.quest.xpReward)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = OpenGymTheme.spacing.sm)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuestMark(metric = row.quest.metric, isComplete = true)

        Text(
            modifier = Modifier.weight(1f),
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = stringResource(R.string.quest_log_quest_reward, row.quest.xpReward),
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What a quest pays, as an outlined pill.
 *
 * Outlined rather than filled: the accent's one filled use on any screen is the primary action,
 * and seven filled pills down a board would spend the whole of it. The figure keeps the accent
 * as *ink*, which is the treatment `PrValue` takes and for the same reason — a number that
 * matters is not a surface.
 */
@Composable
private fun RewardPill(
    xp: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.quest_log_quest_reward, xp),
        style = OpenGymTheme.typography.numericSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .border(
                width = HAIRLINE_WIDTH,
                color = MaterialTheme.colorScheme.primary,
                shape = PillShape,
            )
            .padding(
                horizontal = OpenGymTheme.spacing.md,
                vertical = OpenGymTheme.spacing.xs,
            ),
    )
}

/**
 * How a quest's bar travels to its value.
 *
 * Slow enough to be watched and short enough not to be waited on. It runs once per row on the
 * frame the board appears, so the seven of them read as the board filling in rather than as
 * seven separate animations — they share a spec and start together, which is what makes that
 * true.
 *
 * [reducedMotion] snaps instead. Not a shorter tween: a user who has asked the system for no
 * animation is asking for the answer, and a fast one is still one.
 */
private fun questBarSpec(reducedMotion: Boolean): AnimationSpec<Float> =
    if (reducedMotion) snap() else tween(durationMillis = QUEST_BAR_MILLIS)

private const val QUEST_BAR_MILLIS = 700
