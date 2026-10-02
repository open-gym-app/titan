package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ExerciseStats
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.SessionStats
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.format.formatAsElapsed
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.format.formatVolumeValue
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.PillShape
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration.Companion.seconds

/*
 * The two ways a figure is shown.
 *
 * A [StatTile] is a box: an eyebrow, a large mono figure, a unit. It is what a screen puts
 * up when the number is the point — the summary's four-up grid, the stats hub.
 *
 * A [StatChip] is a word: a small pill carrying a label and a figure inline. It is what a
 * *row* carries when the numbers are context for something else — the duration and set count
 * under a session's name in a list.
 *
 * Both announce as one node. A label and a figure that are separate nodes to a screen reader
 * are read as two unrelated fragments, and "Volume" then "4,820 kg" three swipes apart is
 * not the same information.
 */

/**
 * One figure in a box: `VOLUME`, `4,820`, `kg`.
 *
 * The unit is a separate parameter rather than part of [value] because it is set in a
 * different type role — small, quiet, under the figure — and because a tile whose value
 * carried its own unit would be laid out by string length rather than by the grid.
 *
 * @param label the eyebrow. Sentence case; [Eyebrow] uppercases it.
 * @param value the figure. Always a numeric role — see CONTRIBUTING.md § Using the design
 *   system — so a grid of tiles lines up on the digit rather than on the glyph.
 * @param unit what the figure is measured in, or a qualifier where there is no unit:
 *   `kg`, `total`, `done`. Null draws nothing and the tile closes up.
 * @param accented paints the figure in the accent. For the one tile on a screen that is a
 *   result rather than a fact — a personal record, a streak still running. Spending it on
 *   the whole grid is what stops it meaning anything on any of them.
 * @param badge a tag beside the eyebrow: `NEW` on a record broken this session, `SOON` on one
 *   about to be. On the eyebrow's own line rather than floated over the corner, so it cannot
 *   sit on top of a five-digit figure on a narrow tile, and folded into the tile's spoken
 *   description — the tile speaks as one node, so a badge drawn as a child of it would be
 *   silent.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accented: Boolean = false,
    badge: String? = null,
) {
    val figure = if (unit == null) value else stringResource(R.string.figure_with_unit, value, unit)
    val spoken = stringResource(
        R.string.stat_description,
        label,
        listOfNotNull(badge, figure).joinToString(stringResource(R.string.spoken_separator)),
    )

    TitanCard(modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Column(modifier = Modifier.padding(TitanTheme.spacing.card)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow(text = label, modifier = Modifier.weight(1f))
                if (badge != null) TitanBadge(text = badge, tone = BadgeTone.ACCENT)
            }
            Text(
                text = value,
                style = TitanTheme.typography.numericLarge,
                color = if (accented) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TitanTheme.spacing.xs),
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = TitanTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One figure inline, in a pill: `12 sets`, `52m`, `4,820 kg`.
 *
 * The label follows the figure rather than preceding it, which is the opposite of
 * [StatTile], because a chip is read left to right in a sentence of other chips and the
 * figures are what the eye is scanning for. What a screen reader hears keeps that order —
 * "12 sets", not "sets, 12" — so the spoken chip is the drawn one: Accessibility Scanner
 * holds a node's label to the text it can see on it, and a Voice Access user says what they
 * read.
 *
 * Not [TitanBadge]: a badge is a *tag* — a fixed word, uppercased, carrying no data — and
 * this carries a number in a numeric role. They look similar on purpose and are not the same
 * thing.
 *
 * @param description what a screen reader is given instead of the drawn text, for a chip whose
 *   figure carries a *glyph* the reader cannot make sense of. `↑ 18%` is announced as an arrow
 *   followed by a number, which says nothing; `Up 18 percent` says what the arrow means. The
 *   one caller is `ChangeChip`, and the parameter is here rather than a second pill in the
 *   chart package because a near-copy of this is how two chips on one screen end up different
 *   heights.
 */
@Composable
fun StatChip(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    accented: Boolean = false,
    description: String? = null,
) {
    val container: Color = if (accented) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content: Color = if (accented) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // The label is quieter than the figure beside it, by role rather than by alpha: a
    // translucent copy of the content colour is a pair `ColorSchemeContrastTest` never sees.
    val labelColor: Color = if (accented) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val spoken = description
        ?: label?.let { stringResource(R.string.figure_with_unit, value, it) }
        ?: value

    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .background(color = container, shape = PillShape)
            .padding(horizontal = TitanTheme.spacing.md, vertical = TitanTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Text(text = value, style = TitanTheme.typography.numericSmall, color = content, maxLines = 1)
        if (label != null) {
            Text(
                text = label,
                style = TitanTheme.typography.numericSmall,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * A personal-record tag: `PR`, or `PR · 1RM` when the record's kind is worth naming.
 *
 * The accent, always. A PR is the one thing on a summary the user came back to see, and it
 * is why the rest of the screen is neutral.
 */
@Composable
fun PrBadge(
    modifier: Modifier = Modifier,
    type: PrType? = null,
) {
    val badge = stringResource(R.string.pr_badge)
    val text = if (type == null) badge else stringResource(R.string.badge_with_qualifier, badge, type.label())
    // "P R dot one R M" is what TalkBack makes of the drawn text. The description opens with
    // the letters — "PR" is what a lifter says, and a label that starts with the drawn text is
    // one Voice Access matches and Accessibility Scanner accepts — and then says the words the
    // abbreviation stands for, which is the whole reason a badge may abbreviate.
    val spoken = if (type == null) {
        stringResource(R.string.pr_description_short)
    } else {
        stringResource(R.string.pr_description, type.label())
    }

    TitanBadge(
        text = text,
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        tone = BadgeTone.ACCENT,
    )
}

/**
 * The three figures a finished session is summarised by, as a row of chips.
 *
 * Takes [SessionStats] rather than three strings because that is what the domain hands the
 * caller and because the order — duration, sets, volume — is a decision this component gets
 * to make once instead of every list making it again.
 *
 * @param showDuration false drops the first chip. For a caller whose own chrome already carries
 *   the clock — the active session, whose header ticks one directly above this row — where the
 *   same number twice on one screen is one of them being ignored. A parameter rather than two
 *   components for the same reason [SessionStatGrid] takes `musclesWorked`: the decision is
 *   about what the *surrounding* screen already says, which is not a fact a row of chips has.
 * @param announces whether the row is a **live region** — the active session's set counter,
 *   which moves every time a set is ticked off somewhere else on the screen, and which TalkBack
 *   would otherwise never mention. Announcing, the row is one node saying "5 sets · 2,800 kg" —
 *   the chips' own words in the chips' own order, one sentence per set logged, rather than two
 *   chips changing at once and being read as two interruptions. A finished session's row in a
 *   list is not live: nothing about it changes.
 */
@Composable
fun SessionStatChips(
    stats: SessionStats,
    modifier: Modifier = Modifier,
    showDuration: Boolean = true,
    announces: Boolean = false,
) {
    val duration = stats.durationSeconds.seconds.formatAsElapsed()
    val sets = stringResource(
        R.string.figure_with_unit,
        formatCount(stats.totalSets),
        pluralStringResource(R.plurals.stat_sets_short, stats.totalSets),
    )
    val volume = stringResource(
        R.string.figure_with_unit,
        formatVolumeValue(stats.totalVolume),
        LocalUnitSystem.current.weightSuffix,
    )
    val spoken = listOfNotNull(duration.takeIf { showDuration }, sets, volume)
        .joinToString(stringResource(R.string.exercise_meta_separator))

    Row(
        modifier = if (announces) {
            modifier.clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            }
        } else {
            modifier
        },
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
    ) {
        if (showDuration) {
            StatChip(value = duration)
        }
        StatChip(
            value = formatCount(stats.totalSets),
            label = pluralStringResource(R.plurals.stat_sets_short, stats.totalSets),
        )
        StatChip(
            value = formatVolumeValue(stats.totalVolume),
            label = LocalUnitSystem.current.weightSuffix,
        )
    }
}

/**
 * The same figures as a four-up grid of tiles: the shape the session summary opens with.
 *
 * Two columns rather than four, because a 360dp screen minus its margins gives each tile
 * 160dp and a five-digit volume in `numericLarge` needs most of it.
 *
 * @param musclesWorked how many muscle groups the session touched, or **null** to draw the
 *   duration in that slot instead. Four tiles either way, because a grid with a hole in it is
 *   not a grid — this chooses which of the five figures the fourth tile carries.
 *
 *   The two callers differ in what their header already says. A session summary heads itself
 *   with `10:14 → 11:06 · 52:14`, so a duration tile under it is the same number twice and the
 *   muscle count is the figure with nowhere else to go; a stats hub or a live session has no
 *   such header and wants the clock. Passing the count rather than a flag is what keeps this
 *   from having to reach for the muscle data itself, which is a different query from the one
 *   that produced [stats].
 */
@Composable
fun SessionStatGrid(
    stats: SessionStats,
    modifier: Modifier = Modifier,
    musclesWorked: Int? = null,
) {
    val unitSuffix = LocalUnitSystem.current.weightSuffix

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            StatTile(
                label = stringResource(R.string.stat_volume),
                value = formatVolumeValue(stats.totalVolume),
                unit = unitSuffix,
                modifier = Modifier.weight(1f),
            )
            if (musclesWorked == null) {
                StatTile(
                    label = stringResource(R.string.stat_duration),
                    value = stats.durationSeconds.seconds.formatAsElapsed(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                StatTile(
                    label = stringResource(R.string.stat_muscles),
                    value = formatCount(musclesWorked),
                    unit = stringResource(R.string.stat_muscles_unit),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            StatTile(
                label = stringResource(R.string.stat_sets),
                value = formatCount(stats.totalSets),
                unit = stringResource(R.string.stat_sets_unit),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stat_exercises),
                value = formatCount(stats.exerciseBreakdown.size),
                unit = stringResource(R.string.stat_exercises_unit),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private val PREVIEW_STATS = SessionStats(
    totalSets = 12,
    totalVolume = 4_820_000_000,
    durationSeconds = 3_144,
    exerciseBreakdown = List(8) { index ->
        ExerciseStats(sessionExerciseId = index + 1L, totalSets = 3, totalVolume = 600_000_000)
    },
)

@Preview(name = "Stats - summary grid", widthDp = 360)
@Composable
fun SessionStatGridPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
        ) {
            SessionStatGrid(stats = PREVIEW_STATS, modifier = Modifier.fillMaxWidth())
            SessionStatChips(stats = PREVIEW_STATS)
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm)) {
                PrBadge()
                PrBadge(type = PrType.ESTIMATED_1RM)
                StatChip(value = "12", label = "day streak", accented = true)
            }
        }
    }
}

@Preview(name = "Stats - summary grid, dark", widthDp = 360)
@Composable
fun SessionStatGridDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
        ) {
            SessionStatGrid(stats = PREVIEW_STATS, modifier = Modifier.fillMaxWidth())
            SessionStatChips(stats = PREVIEW_STATS)
            Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm)) {
                PrBadge()
                PrBadge(type = PrType.MAX_WEIGHT)
                StatChip(value = "12", label = "day streak", accented = true)
            }
        }
    }
}

@Preview(name = "Stats - a single accented tile", widthDp = 360)
@Composable
fun StatTileAccentedPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(TitanTheme.spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        ) {
            StatTile(label = "Streak", value = "12", unit = "days", accented = true, modifier = Modifier.width(150.dp))
            StatTile(label = "Best", value = "21", unit = "days", modifier = Modifier.width(150.dp))
        }
    }
}
