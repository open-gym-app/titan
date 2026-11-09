package com.titan.feature.home.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.titan.core.ui.component.LevelProgressBar
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.home.LevelState
import com.titan.feature.home.R

/**
 * Where the user stands, as the first line on the screen: `LVL 14`, the points into it, and a
 * bar under both.
 *
 * ```text
 * LVL 14                                           240 / 331 XP
 * ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
 * ```
 *
 * ### A header rather than a card
 *
 * Everything else on this screen is a bordered card with a heading over it, and this is
 * deliberately neither. It is the app's own chrome — the wireframes draw the level in the top
 * bar, beside the settings gear — and Home is where it lands because the tab bar's title row is
 * `:app`'s and shared by four tabs, three of which have no business carrying a level. Drawn as
 * a card it would compete with the resume banner for the top of the screen; drawn as a header
 * it is a line of standing above the thing the user came to do.
 *
 * ### The bar and the figure beside it are the same fact
 *
 * The figure is points *into* the level, not the lifetime total, because it is what the bar is
 * a picture of — the two are read together and a total beside a proportion is two answers to
 * one question. The lifetime figure belongs to the quest log (#104), where there is room to say
 * what it bought.
 *
 * ### One node to a screen reader
 *
 * Three pieces of text and a bar, read as one sentence: `Level 14, 360 points to the next level`.
 * A reader that walked them separately would hear `LVL 14`, then a bare proportion, then the
 * same thing a third time from the bar's own description — the call `PersonalRecordRow` and the
 * summary's stat tiles both make, for the same reason.
 */
@Composable
fun LevelHeader(
    level: LevelState,
    modifier: Modifier = Modifier,
) {
    val remaining = (level.xpForLevel - level.xpIntoLevel).coerceAtLeast(0)
    val spoken = pluralStringResource(
        R.plurals.home_level_description,
        remaining,
        level.level,
        remaining,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_level, level.level),
                style = TitanTheme.typography.numeric,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.home_level_progress, level.xpIntoLevel, level.xpForLevel),
                style = TitanTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // The accent, through the component's own default: this is a standing rather than a
        // crossing, so it is drawn in the app's colour and not in the celebration palette the
        // recap reaches for. A lambda because the component takes one — nothing animates it
        // here, and a level that moved while Home was open moved because a workout finished on
        // the screen before this one.
        LevelProgressBar(progress = { level.progress }, contentDescription = spoken)
    }
}
