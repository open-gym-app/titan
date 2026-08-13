package com.opengym.core.ui.component

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ExerciseStats
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatAsElapsed
import com.opengym.core.ui.format.formatVolumeValue
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.core.ui.theme.PillShape
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
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accented: Boolean = false,
) {
    val spoken = stringResource(R.string.stat_description, label, listOfNotNull(value, unit).joinToString(" "))

    OpenGymCard(modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Column(modifier = Modifier.padding(OpenGymTheme.spacing.card)) {
            Eyebrow(text = label)
            Text(
                text = value,
                style = OpenGymTheme.typography.numericLarge,
                color = if (accented) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = OpenGymTheme.spacing.xs),
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = OpenGymTheme.typography.numericSmall,
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
 * figures are what the eye is scanning for.
 *
 * Not [OpenGymBadge]: a badge is a *tag* — a fixed word, uppercased, carrying no data — and
 * this carries a number in a numeric role. They look similar on purpose and are not the same
 * thing.
 */
@Composable
fun StatChip(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    accented: Boolean = false,
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
    val spoken = label?.let { stringResource(R.string.stat_description, it, value) } ?: value

    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = spoken }
            .background(color = container, shape = PillShape)
            .padding(horizontal = OpenGymTheme.spacing.md, vertical = OpenGymTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Text(text = value, style = OpenGymTheme.typography.numericSmall, color = content, maxLines = 1)
        if (label != null) {
            Text(
                text = label,
                style = OpenGymTheme.typography.numericSmall,
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
fun PrBadge(modifier: Modifier = Modifier, type: PrType? = null) {
    val badge = stringResource(R.string.pr_badge)
    val text = if (type == null) badge else "$badge · ${type.label()}"
    // "P R dot one R M" is what TalkBack makes of the drawn text. The description says the
    // words the abbreviation stands for, which is the whole reason a badge may abbreviate.
    val spoken = if (type == null) {
        stringResource(R.string.pr_description_short)
    } else {
        stringResource(R.string.pr_description, type.label())
    }

    OpenGymBadge(
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
 */
@Composable
fun SessionStatChips(stats: SessionStats, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
    ) {
        StatChip(value = stats.durationSeconds.seconds.formatAsElapsed())
        StatChip(value = stats.totalSets.toString(), label = stringResource(R.string.stat_sets_short))
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
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
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
                    value = musclesWorked.toString(),
                    unit = stringResource(R.string.stat_muscles_unit),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
            StatTile(
                label = stringResource(R.string.stat_sets),
                value = stats.totalSets.toString(),
                unit = stringResource(R.string.stat_sets_unit),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stat_exercises),
                value = stats.exerciseBreakdown.size.toString(),
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
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
        ) {
            SessionStatGrid(stats = PREVIEW_STATS, modifier = Modifier.fillMaxWidth())
            SessionStatChips(stats = PREVIEW_STATS)
            Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm)) {
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
    OpenGymTheme(theme = ThemePreference.DARK) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
        ) {
            SessionStatGrid(stats = PREVIEW_STATS, modifier = Modifier.fillMaxWidth())
            SessionStatChips(stats = PREVIEW_STATS)
            Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm)) {
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
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(OpenGymTheme.spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            StatTile(label = "Streak", value = "12", unit = "days", accented = true, modifier = Modifier.width(150.dp))
            StatTile(label = "Best", value = "21", unit = "days", modifier = Modifier.width(150.dp))
        }
    }
}
