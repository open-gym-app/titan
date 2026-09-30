package com.titan.feature.progression.achievements.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.titan.core.ui.component.ACHIEVEMENT_MEDAL_SIZE
import com.titan.core.ui.component.AchievementMedal
import com.titan.core.ui.component.rememberMedalCeremony
import com.titan.core.ui.format.formatDayAndMonth
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.achievements.AchievementEntry
import com.titan.feature.progression.achievements.label
import kotlinx.coroutines.launch

/**
 * The shelf: every milestone in the catalogue as a medal, three to a row.
 *
 * ### Locked medals are drawn, and say only that they are locked
 *
 * Unchanged from the grid this replaced, and still the point: a shelf listing only what the user
 * holds would show a new user nothing at all, which is the opposite of what a case of things to
 * earn is for. A locked medal is a blank disc with a padlock — it reveals nothing about the
 * threshold behind it, which is what #104 asks for, and it reads as a slot waiting to be filled
 * rather than as a failure.
 *
 * ### Three to a row, not two
 *
 * The cells used to hold a name and the word `Locked`, which needed the width. A medal is a
 * picture with a caption, and three of them across a 360dp screen puts the whole catalogue on one
 * screen without scrolling — so the shelf reads as a *set*, which is the thing a collection has to
 * do before anybody wants to complete it.
 *
 * Rows are laid out at [IntrinsicSize.Min] and every caption reserves two lines. The old grid let
 * each cell size itself, so a row holding one one-line name and one two-line name drew two boxes
 * of different heights side by side; a shelf of objects has to sit on a level shelf.
 *
 * ### A held medal can be struck again
 *
 * Tapping one replays its ceremony. It changes nothing and stores nothing — which is exactly why
 * it is worth having: it is the one place in the app where something is *only* fun, and a
 * collection you can pick things up off is a collection rather than a report. Locked medals are
 * not clickable: an affordance that does nothing is worse than none, and there is nothing to
 * replay before there is something to replay.
 */
@Composable
internal fun MedalShelf(
    entries: List<AchievementEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        entries.chunked(COLUMNS).forEach { row ->
            Row(
                // The shelf is level: every cell in a row is as tall as the tallest caption in it.
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
            ) {
                row.forEach { entry ->
                    MedalCell(entry = entry, modifier = Modifier.weight(1f))
                }
                // Keeps a trailing odd medal at one column's width instead of letting it stretch
                // across the row, which would make the last milestone look like a different kind
                // of thing from the ones above it.
                repeat(COLUMNS - row.size) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun MedalCell(
    entry: AchievementEntry,
    modifier: Modifier = Modifier,
) {
    val name = entry.achievement.label()
    val spoken = entry.spokenState()
    val scope = rememberCoroutineScope()
    val ceremony = rememberMedalCeremony()
    val replayLabel = stringResource(R.string.achievements_replay_action)
    val interactions = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .then(
                if (entry.isUnlocked) {
                    // Labelled, so a screen reader offers "strike it again" rather than
                    // announcing a button with no name that appears to change something. The label
                    // sits on the action; the cell's own sentence is set below.
                    //
                    // No ripple: a rectangle of tint washing over a round medal is the wrong shape
                    // for the object, and the strike it sets off is already the feedback — a
                    // press that visibly does something does not need to be told it was received.
                    Modifier.clickable(
                        interactionSource = interactions,
                        indication = null,
                        onClickLabel = replayLabel,
                        onClick = { scope.launch { ceremony.play() } },
                    )
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
    ) {
        AchievementMedal(
            tier = entry.tier,
            metric = entry.achievement.metric,
            isUnlocked = entry.isUnlocked,
            size = ACHIEVEMENT_MEDAL_SIZE,
            ceremony = ceremony,
        )

        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            // Two lines whether or not the name needs them, so three medals in a row stand on the
            // same line and the shelf does not ripple.
            minLines = CAPTION_LINES,
            color = if (entry.isUnlocked) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The whole cell as one sentence: what it is, whether it is held, its metal, and when it landed.
 *
 * Internal because the hero speaks the same way about the same milestone, and two spellings of
 * one fact is how a screen ends up announcing "bronze medal" in one place and "Bronze" in another.
 */
@Composable
internal fun AchievementEntry.spokenState(): String {
    if (!isUnlocked) {
        return stringResource(R.string.achievements_locked_description, achievement.label())
    }

    val earnedAt = unlockedAt
    return if (earnedAt == null) {
        // A held milestone with no date on it — a row imported or restored without one. It is
        // still earned, and the sentence simply stops one clause early.
        stringResource(R.string.achievements_unlocked_description, achievement.label(), tier.label())
    } else {
        stringResource(
            R.string.achievements_unlocked_dated_description,
            achievement.label(),
            tier.label(),
            formatDayAndMonth(earnedAt),
        )
    }
}

/** Three to a row: the six that ship fit on one screen, which is what makes them read as a set. */
private const val COLUMNS = 3

/** Every caption reserves two lines, so a row of medals stands on one shelf. */
private const val CAPTION_LINES = 2
