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
import androidx.compose.ui.text.style.TextAlign
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.quests.AchievementEntry
import com.opengym.feature.progression.quests.label

/**
 * The achievements, held and not, two to a row.
 *
 * ### Locked entries are drawn, and say only that they are locked
 *
 * Every catalogue entry gets a cell. A grid that listed only what the user holds would show a new
 * user nothing at all, which is the opposite of what a board of things to earn is for — and it is
 * what #104 means by "distinguishes locked from unlocked with a correct total".
 *
 * A locked cell shows the milestone's **name** and the word `Locked`, and nothing else. #104 is
 * explicit that it reveals nothing about how to unlock it beyond what the catalogue defines, and
 * the catalogue's names are already public — `A week-long streak` says what it is without saying
 * what the threshold behind it is.
 *
 * ### A `Row`-built grid rather than `LazyVerticalGrid`
 *
 * The whole screen is one scrolling column, and a lazy grid inside a scrolling parent is either an
 * unbounded-height crash or a nested scroll region the user has to fight. The catalogue is six
 * entries and bounded by the app's own data, so laying the rows out eagerly costs nothing.
 */
@Composable
internal fun AchievementGrid(
    entries: List<AchievementEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        entries.chunked(COLUMNS).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            ) {
                pair.forEach { entry ->
                    AchievementCell(entry = entry, modifier = Modifier.weight(1f))
                }
                // Keeps a trailing odd cell at one column's width instead of letting it stretch
                // across the row, which would make the last milestone look like a different kind
                // of thing from the five above it.
                repeat(COLUMNS - pair.size) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun AchievementCell(
    entry: AchievementEntry,
    modifier: Modifier = Modifier,
) {
    val name = entry.achievement.label()
    val spoken = if (entry.isUnlocked) {
        stringResource(
            R.string.quest_log_achievement_unlocked_description,
            name,
        )
    } else {
        stringResource(
            R.string.quest_log_achievement_locked_description,
            name,
        )
    }

    OpenGymCard(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        accented = entry.isUnlocked,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (entry.isUnlocked) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!entry.isUnlocked) {
                Eyebrow(
                    text = stringResource(
                        R.string.quest_log_achievement_locked,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Two to a row: six entries in three rows, and a name that fits without wrapping to four lines. */
private const val COLUMNS = 2
