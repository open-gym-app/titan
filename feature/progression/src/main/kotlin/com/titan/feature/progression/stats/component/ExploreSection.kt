package com.titan.feature.progression.stats.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.titan.core.ui.component.IconTile
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.test.TitanTestTags
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R

/**
 * The five ways out of the hub.
 *
 * ### It is the only way into two of them
 *
 * The bottom bar is deliberately four tabs — see `TopLevelDestination` — so the quest log
 * (#104) and the body measurements screen (#109) have no other entrance in the app. That is
 * why this section is the hub's responsibility rather than a nicety: without a row here, two
 * finished screens ship unreachable.
 *
 * ### No accent, and no counts
 *
 * The tiles are neutral. Five accented marks in a stack would spend the accent five times on
 * the least interesting decision on the screen, and the figures above them are what the accent
 * is for here. The captions describe the destination rather than counting what is behind it:
 * a count would be a sixth query, over all of history rather than over the selected window,
 * and a row that said `24 PRs` beside a grid saying `7` would read as one of them being wrong.
 */
@Composable
fun ExploreSection(
    actions: StatsExploreActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        SectionHeading(
            text = stringResource(R.string.stats_explore_heading),
            modifier = Modifier.padding(bottom = TitanTheme.spacing.xs),
        )
        ExploreRow(
            icon = Icons.Outlined.EmojiEvents,
            title = stringResource(R.string.explore_records_title),
            caption = stringResource(R.string.explore_records_caption),
            onClick = actions.onOpenRecords,
        )
        ExploreRow(
            icon = Icons.AutoMirrored.Outlined.ShowChart,
            title = stringResource(R.string.explore_exercise_title),
            caption = stringResource(R.string.explore_exercise_caption),
            onClick = actions.onOpenExerciseProgression,
            // The only row `:benchmark` presses: the chart behind it is one of the two renders
            // #42 puts a budget on. See `TitanTestTags`.
            modifier = Modifier.testTag(TitanTestTags.STATS_EXPLORE_EXERCISE),
        )
        ExploreRow(
            icon = Icons.Outlined.Shield,
            title = stringResource(R.string.explore_quests_title),
            caption = stringResource(R.string.explore_quests_caption),
            onClick = actions.onOpenQuestLog,
        )
        ExploreRow(
            icon = Icons.Outlined.MonitorWeight,
            title = stringResource(R.string.explore_measurements_title),
            caption = stringResource(R.string.explore_measurements_caption),
            onClick = actions.onOpenMeasurements,
        )
        ExploreRow(
            icon = Icons.Outlined.CalendarMonth,
            title = stringResource(R.string.explore_calendar_title),
            caption = stringResource(R.string.explore_calendar_caption),
            onClick = actions.onOpenCalendar,
        )
    }
}

/**
 * One row: a mark, a name, a line saying what is behind it, and a chevron.
 *
 * `mergeDescendants` rather than a hand-written description: the title and the caption are
 * already the sentence a reader wants, in the order they are drawn, and the click stays on the
 * node that carries them. `clearAndSetSemantics` here would wipe the click along with the text
 * — the mistake `TrainingCalendar`'s day cell documents.
 */
@Composable
private fun ExploreRow(
    icon: ImageVector,
    title: String,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TitanCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            // Inside the card and before the padding, the way Home's resume banner builds its
            // click: on the modifier handed to [TitanCard] it would sit outside the
            // `Surface`'s clip and ripple square over a rounded card, and after the padding it
            // would leave a dead border the width of the row's inset.
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { }
                .padding(
                    horizontal = TitanTheme.spacing.xl,
                    vertical = TitanTheme.spacing.lg,
                ),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = icon,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                // Neither line is capped: the caption is the sentence that says what the row
                // opens, and at twice the font size it is wider than the row holds. One line
                // with an ellipsis would cut it after two or three words, so the row grows
                // taller instead.
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = caption,
                    style = TitanTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                // The row already announces itself as a control; a chevron that named itself
                // would be a second thing to swipe past on every one of the five.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Where each explore row goes.
 *
 * Five lambdas as one parameter, the shape `:feature:history`'s `TemplateEditor` takes: they
 * only make sense together, and they keep the screen's signature readable as the hub grows.
 *
 * Every one is hoisted even though four of the five destinations live in this module. The
 * `NavController` belongs to `:app`, and a feature that reaches for it to make one push is one
 * refactor away from reaching for another feature's destination through it —
 * [onOpenCalendar], which lands in `:feature:history`, is exactly that case.
 */
data class StatsExploreActions(
    /** The personal-record list (#103). */
    val onOpenRecords: () -> Unit,
    /** Per-exercise progression (#102), which reads over the same shared timeframe. */
    val onOpenExerciseProgression: () -> Unit,
    /** The quest log (#104). The hub is the only way in — see [ExploreSection]. */
    val onOpenQuestLog: () -> Unit,
    /** Body measurements (#109). The hub is the only way in. */
    val onOpenMeasurements: () -> Unit,
    /** The training calendar, which is the Sessions tab's own (#24). */
    val onOpenCalendar: () -> Unit,
)
