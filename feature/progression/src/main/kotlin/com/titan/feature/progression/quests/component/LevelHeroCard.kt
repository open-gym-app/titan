package com.titan.feature.progression.quests.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.gamification.LevelTitle
import com.titan.core.domain.model.UserProgress
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.LaurelWreath
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.quests.label

/**
 * The screen's hero: what level the user is, how far into it, and what that rank is called.
 *
 * ### A wreath, not a ring and not a bar
 *
 * Three shapes were tried here and only one of them is right. `LevelProgressBar`, which the
 * session summary uses, draws a level being *crossed* — a bar that fills and sweeps is a thing
 * happening. `ProgressRing` draws a level being *held*, which is closer, but a ring is the shape
 * this app measures things in: it is on the stats hub and in half a dozen charts, so the rank read
 * as one more figure among figures.
 *
 * [LaurelWreath] is neither. It is an *award*, it cannot be mistaken for a chart, and it still
 * carries the progress honestly because its leaves fill as the XP does. That component's KDoc
 * carries the full argument.
 *
 * ### The rank is the headline, the level is the figure
 *
 * "Iron Disciple" above "LVL 5" rather than the other way round. The number is what the app
 * computes; the rank is what the user tells someone. Both are drawn because the number is what
 * makes the rank legible — a rank with no scale behind it is a word.
 *
 * A level past the end of the rank table keeps the last rank rather than blanking, which
 * `LevelTitles.forLevel` guarantees; [title] is nullable here only for the frame before the store
 * has answered, and the eyebrow is simply absent for it rather than showing a placeholder that
 * would flash.
 */
@Composable
internal fun LevelHeroCard(
    progress: UserProgress,
    title: LevelTitle?,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth(), accented = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TitanTheme.spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        ) {
            LaurelWreath(
                progress = progress.levelFraction,
                label = stringResource(R.string.quest_log_level, progress.level),
                valueLabel = stringResource(
                    R.string.quest_log_level_xp,
                    formatCount(progress.xpIntoLevel),
                    formatCount(progress.xpForNextLevel),
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow(text = stringResource(R.string.quest_log_level_short))
                    Text(
                        text = formatCount(progress.level),
                        style = TitanTheme.typography.numericDisplay,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
            ) {
                title?.let {
                    Text(
                        text = it.label(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.quest_log_level_xp,
                        formatCount(progress.xpIntoLevel),
                        formatCount(progress.xpForNextLevel),
                    ),
                    style = TitanTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.quest_log_total_xp, formatCount(progress.totalXp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
