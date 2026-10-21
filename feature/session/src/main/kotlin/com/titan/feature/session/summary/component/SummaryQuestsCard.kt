package com.titan.feature.session.summary.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestPeriod
import com.titan.core.ui.component.QUEST_MARK_COMPACT_SIZE
import com.titan.core.ui.component.QuestMark
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.theme.LocalReducedMotion
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R
import com.titan.feature.session.summary.SummaryQuest

/**
 * The quests this workout cleared, one row each.
 *
 * ### Why they are not left in the XP breakdown
 *
 * The breakdown already carries a `quests` line, and it says only *how much they paid* — which is
 * the one thing about a cleared quest nobody cares about. A lifter who has just finished their
 * third session of the week wants to be told **that they finished it**, by name, and the
 * breakdown's `+140 · 2 quests` says neither which two nor that either of them was a week's work.
 * So the points appear twice on this screen in two roles, exactly as the milestones do: as a
 * contribution to the total above, and as a footnote to the thing that was actually done.
 *
 * This card is the whole of the fix for a real gap: before it, clearing a quest was invisible at
 * the moment it happened, and the only way to find out was to open the quest log afterwards and
 * notice a row had moved.
 *
 * ### The rows land one after another
 *
 * Each row fades and rises into place, staggered behind the one above it. A session that clears
 * two quests should read as two things happening rather than as a card that was already there —
 * this is the moment they were earned, and it is the only moment the app gets to say so. The
 * stagger is short enough that a single-quest card, which is nearly all of them, still feels
 * immediate.
 *
 * Under reduced motion every row is simply drawn in place, the contract every animation in this
 * codebase is under. See `LocalReducedMotion`.
 *
 * ### The cadence is on the row, not implied by it
 *
 * `This week` / `This month` beside each name. Clearing a monthly quest is the rarer event by an
 * order of magnitude and a row that read the same for both would flatten the two into one kind of
 * news.
 *
 * ### A quest this build does not recognise still draws
 *
 * The catalogue is `Quests.ALL` in the domain and the words live here, so an id with no string is
 * a real possibility — a row written by a newer build, read by an older one. It draws with a
 * fallback title rather than being dropped, on the reasoning `AchievementsUnlockedCard` and
 * `SummaryRecord` both use: it is a thing the user did, and a screen that silently omitted it
 * would be under-reporting their session.
 *
 * The caller draws nothing when the list is empty, which is most sessions.
 */
@Composable
internal fun SummaryQuestsCard(
    quests: List<SummaryQuest>,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(TitanTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        ) {
            quests.forEachIndexed { index, quest ->
                QuestClearedRow(quest = quest, index = index)
            }
        }
    }
}

@Composable
private fun QuestClearedRow(
    quest: SummaryQuest,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current

    // Zero until the frame after composition, then one — the shape `ConfettiBurst` and
    // `CountUpNumber` both use: an animation seeded at its own destination never runs. Saveable,
    // so a rotation does not replay the entrance the user has already seen.
    var target by rememberSaveable(quest.id) { mutableFloatStateOf(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(quest.id) { target = 1f }

    val entrance by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else ENTRANCE_MILLIS,
            delayMillis = if (reducedMotion) 0 else index * STAGGER_MILLIS,
        ),
        label = "quest cleared",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance
                // Rises rather than falls: what is being announced arrived from below, the way
                // the row would have if the card had grown to make room for it.
                translationY = (1f - entrance) * ENTRANCE_RISE.toPx()
            },
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The board's own mark, in its cleared state — the same tick the quest log now shows
        // against this row, so the two screens agree about what "done" looks like.
        QuestMark(
            metric = quest.metric(),
            isComplete = true,
            size = QUEST_MARK_COMPACT_SIZE,
            // The tick overshoots and settles. The mark is the thing that says "done", so it is
            // the thing worth landing rather than merely appearing.
            modifier = Modifier.graphicsLayer {
                val pop = MARK_OVERSHOOT * entrance * (1f - entrance) * OVERSHOOT_GAIN
                scaleX = entrance + pop
                scaleY = entrance + pop
            },
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = quest.title(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = quest.cadence(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.summary_xp_source_amount, quest.xp),
            style = TitanTheme.typography.numericSmall,
            color = TitanTheme.celebration.xp,
        )
    }
}

/**
 * The quest's words, by id.
 *
 * Duplicated from `:feature:progression`, which draws the same seven on the board. A string
 * resource belongs to one module, and the alternative — moving them to `:core:ui` — would give
 * the design-system module knowledge of the gamification catalogue, which is the worse edge. The
 * same trade the milestone names are already under; see `AchievementsUnlockedCard`.
 */
@Composable
private fun SummaryQuest.title(): String =
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

/** Which deadline was met. */
@Composable
private fun SummaryQuest.cadence(): String =
    when (period) {
        QuestPeriod.WEEKLY -> stringResource(R.string.summary_quest_weekly)
        QuestPeriod.MONTHLY -> stringResource(R.string.summary_quest_monthly)
    }

/**
 * What the quest counted, for its mark.
 *
 * Derived from the id rather than carried on [SummaryQuest], because the mark is decoration and
 * a fourth field on the model to feed it would be the celebration knowing more about the domain
 * than it draws. An unrecognised id falls back to the commonest metric, which gives it a mark
 * rather than a hole — the same failure mode [title] takes.
 */
private fun SummaryQuest.metric(): QuestMetric =
    when {
        id.endsWith("record") || id.endsWith("records") -> QuestMetric.RECORDS_BROKEN
        id.endsWith("volume") -> QuestMetric.VOLUME_MILLIGRAMS
        else -> QuestMetric.SESSIONS_FINISHED
    }

/** How long one row takes to arrive. */
private const val ENTRANCE_MILLIS = 380

/** How far behind the row above each one starts. */
private const val STAGGER_MILLIS = 110

/** How far a row rises into place. */
private val ENTRANCE_RISE: Dp = 12.dp

/** How far past its size the tick swells at the peak of its arrival, before settling. */
private const val MARK_OVERSHOOT = 0.35f

/** `t * (1 - t)` peaks at 0.25, so the gain turns [MARK_OVERSHOOT] into the actual overshoot. */
private const val OVERSHOOT_GAIN = 4f
