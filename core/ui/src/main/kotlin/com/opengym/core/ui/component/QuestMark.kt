package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Scale
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.QuestMetric
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A quest's mark: the glyph for what it counts, on the tinted round ground its metric owns.
 *
 * ### Why the board has marks at all
 *
 * Seven quests written one under another are seven sentences, and a lifter opening the board
 * wants to know *what kind* of week they are having before they read any of them. The mark
 * answers that at a glance — training, records, tonnage — which is the whole reason the rows are
 * worth decorating. See [OpenGymQuestColors], which argues for the three hues and for why they
 * are not new ones.
 *
 * ### A cleared quest loses its colour, and gains a tick
 *
 * [isComplete] swaps the whole mark: a neutral ground and a check. That is deliberate and it is
 * the opposite of the obvious move, which would be to *brighten* what is done. A board's colour
 * should point at what is still live; a cleared quest has already paid, and drawing it as the
 * loudest thing on the screen would leave a user who has cleared five of seven looking at a wall
 * of celebration with the two that matter hidden in it.
 *
 * The tick is what carries "done" for a reader who cannot see the tint, which is the obligation
 * every encoded colour in this app is under.
 *
 * **It says nothing.** The mark carries no content description: every caller draws it inside a
 * row whose own sentence already names the quest and its state.
 *
 * @param size the mark's diameter. [QUEST_MARK_SIZE] on the board; pass something smaller for a
 *   dashboard summary, where the mark is a hint rather than the row's anchor.
 */
@Composable
fun QuestMark(
    metric: QuestMetric,
    modifier: Modifier = Modifier,
    isComplete: Boolean = false,
    size: Dp = QUEST_MARK_SIZE,
) {
    val quest = OpenGymTheme.quest
    val container = if (isComplete) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        when (metric) {
            QuestMetric.SESSIONS_FINISHED -> quest.sessions
            QuestMetric.RECORDS_BROKEN -> quest.records
            QuestMetric.VOLUME_MILLIGRAMS -> quest.volume
        }
    }
    val tint = if (isComplete) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        when (metric) {
            QuestMetric.SESSIONS_FINISHED -> quest.onSessions
            QuestMetric.RECORDS_BROKEN -> quest.onRecords
            QuestMetric.VOLUME_MILLIGRAMS -> quest.onVolume
        }
    }

    IconTile(
        icon = if (isComplete) Icons.Outlined.Check else metric.icon(),
        modifier = modifier.size(size),
        size = size,
        tint = tint,
        container = container,
        shape = CircleShape,
    )
}

/**
 * The glyph for what a metric counts.
 *
 * A `when` with no `else`: [QuestMetric] is a closed enum, so a fourth metric added to the domain
 * stops this compiling rather than shipping with a blank mark. That is the same forcing function
 * `XpEvent`'s sealed hierarchy provides for the summary's breakdown.
 */
private fun QuestMetric.icon(): ImageVector =
    when (this) {
        QuestMetric.SESSIONS_FINISHED -> Icons.Outlined.FitnessCenter
        QuestMetric.RECORDS_BROKEN -> Icons.Outlined.EmojiEvents
        QuestMetric.VOLUME_MILLIGRAMS -> Icons.Outlined.Scale
    }

/** The mark beside a quest on the board. A shade larger than [ICON_TILE_SIZE], since it is round. */
val QUEST_MARK_SIZE: Dp = 44.dp

/** The mark on a dashboard row, where it hints at the metric rather than anchoring the row. */
val QUEST_MARK_COMPACT_SIZE: Dp = 32.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Quest marks")
@Composable
fun QuestMarkPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        QuestMarkRow()
    }
}

@Preview(name = "Quest marks - dark")
@Composable
fun QuestMarkDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        QuestMarkRow()
    }
}

@Composable
private fun QuestMarkRow() {
    Box(modifier = Modifier.padding(OpenGymTheme.spacing.md)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuestMetric.entries.forEach { metric -> QuestMark(metric = metric) }
            QuestMark(metric = QuestMetric.SESSIONS_FINISHED, isComplete = true)
        }
    }
}
