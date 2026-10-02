package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateSummary
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.ui.R
import com.titan.core.ui.format.formatAsEstimate
import com.titan.core.ui.theme.TitanTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One saved template as every list of them draws it: a mark, the name, what it costs, and
 * what it trains.
 *
 * ```text
 * ┌────┐  Push Day · Upper                    ⋮
 * │ P  │  8 EX · ~55M   STRENGTH
 * └────┘
 * ```
 *
 * ### Why the chip is on the second line rather than out to the right
 *
 * Because the name has to survive a narrow screen. On 320dp — the narrowest Android still runs
 * on — a row with the mark, a chip reading `HYPERTROPHY` and an overflow button out to the right
 * leaves about fifty points for the name, and "Full Body Beginner" arrives as "Full Body …". The
 * chip qualifies the figures beside it as much as it qualifies the title above them, so it costs
 * nothing to put it there, and what it buys is a row whose subject is readable.
 *
 * Shared rather than owned by a screen because three of them render it — the new-session
 * picker (#25), the templates tab (#24) and the template editor's header (#92) — and the
 * figures on it are the kind that drift: an exercise count that counts a superset as one
 * exercise on one screen and two on another is worse than no count.
 *
 * ### What the mark is
 *
 * The template's initial, over a filled square. Not an image: a template has no photo, and
 * [WorkoutTemplate.icon] is a string nothing in the app writes yet — when something does, it
 * belongs here as an overlay in the way [ExerciseImage] draws one, over the same letter.
 * The letter is the design's whole vocabulary of marks (see [EmptyState]'s glyph) and it is
 * the only thing on a list of six templates that makes one findable at a glance.
 *
 * ### What the row says out loud
 *
 * One node, not four. The three figures are drawn as separate `Text`s because they are
 * three type roles, but a screen reader walking a list of templates should hear
 * "Push Day · Upper, 8 exercises, about 55 minutes, strength" as one row and then move to
 * the next — so the parts are cleared and the row carries the whole sentence.
 *
 * @param onClick what tapping the row does, or null for a row that is being shown rather
 *   than offered — the editor's header. A null draws no ripple and takes no click role.
 * @param trailing drawn after the category chip: a chevron, an overflow menu, a checkmark.
 *   The category is *not* a slot, because a row that showed one thing there on one screen
 *   and another elsewhere is what this component exists to prevent.
 */
@Composable
fun TemplateRow(
    summary: TemplateSummary,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val category = summary.template.category
    // Resolved before the modifier chain: `clearAndSetSemantics` takes a plain lambda, not a
    // composable one, so the strings cannot be read inside it.
    val description = summary.spokenDescription()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = TitanTheme.spacing.card, vertical = TitanTheme.spacing.lg),
        // Tight, because the row is four things wide on a 320dp screen — the mark, the name, the
        // chip and whatever the caller puts in [trailing] — and every point spent between them
        // comes off the estimate at the end of the second line.
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The semantics are cleared over *this* block rather than over the whole row, so a
        // control in [trailing] — the templates tab's overflow menu — keeps its own. A clear
        // on the outer row would leave that button unreachable to a screen reader while
        // still drawn, which is the worst of both.
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TemplateMark(name = summary.template.name)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
            ) {
                Text(
                    text = summary.template.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = summary.meta(),
                        style = TitanTheme.typography.numericSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Weighted so that where the two do not both fit — a long estimate next
                        // to `HYPERTROPHY` on a 320dp screen — it is the figures that give way
                        // and not the chip. `fill = false` so a short estimate does not push the
                        // chip out to the margin.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // No chip for a template nobody has categorised — see TemplateCategory,
                    // where the null is explicitly not GENERAL.
                    if (category != null) {
                        TitanBadge(text = category.label())
                    }
                }
            }
        }

        if (trailing != null) trailing()
    }
}

/**
 * The same template as a card, for a strip that scrolls sideways.
 *
 * ```text
 * ┌──────────────┐ ┌──────────────┐
 * │ Push Day     │ │ Pull Day     │
 * │ 8 EX · ~55M  │ │ 7 EX · ~50M  │
 * └──────────────┘ └──────────────┘
 * ```
 *
 * Home's quick-start strip, and nothing else yet. It lives beside [TemplateRow] rather than in
 * the feature because the two have to keep saying the same thing about the same template: the
 * exercise count and the estimate come from the one private helper below, so a card and a row
 * cannot end up five minutes apart on the same workout. What it drops is what a 140dp card has
 * no room for — the mark and the category chip — and it drops nothing that carries a figure.
 *
 * The width is **bounded rather than fixed**: at ordinary sizes every card comes out at the
 * minimum, so a strip of them reads as a rack rather than as a ragged line, and at a large font
 * scale a card grows to hold its own figures instead of clipping them — `8 ex · ~55m` cut off
 * after `8 ex ·` is a row of numbers that says nothing. The name takes two lines and then
 * ellipsises, which is what keeps the growth bounded.
 *
 * @param onClick what tapping the card does. Not optional, unlike [TemplateRow]'s: a card in a
 *   scrolling strip that does nothing is a control the user will try twice.
 * @param enabled false dims the card and takes the click off it, announcing it as disabled.
 *   For a caller whose strip leads somewhere that is currently refusing — Home's, while a
 *   workout is still open — where a live card is a tap that ends in a dialog saying no.
 */
@Composable
fun TemplateCard(
    summary: TemplateSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Resolved before the modifier chain: `semantics` takes a plain lambda, not a composable
    // one, so the strings cannot be read inside it.
    val description = summary.spokenDescription()
    val openLabel = stringResource(R.string.template_start)

    TitanCard(
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .widthIn(min = CARD_WIDTH_MIN, max = CARD_WIDTH_MAX)
            // One fade over the whole card rather than a disabled colour per line: the card is
            // two texts and a border, and dimming them one at a time is three constants that
            // have to agree.
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Column(
            // The click sits *inside* the card, as [TemplateRow]'s and [ExerciseCard]'s do:
            // a `clickable` on the modifier handed to [TitanCard] is applied outside the
            // `Surface`'s own clip, so the ripple would be drawn as a square over a rounded
            // card. Before the padding, so the whole card is the target rather than the text
            // in the middle of it.
            modifier = Modifier
                .clickable(enabled = enabled, onClickLabel = openLabel, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(TitanTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
        ) {
            Text(
                text = summary.template.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary.meta(),
                style = TitanTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The square with the template's opening letter in it.
 *
 * `extraSmall`, the same radius [ExerciseImage] clips a thumbnail to, so a template row and
 * an exercise row in the same list read as the same kind of thing.
 */
@Composable
private fun TemplateMark(name: String) {
    Box(
        modifier = Modifier
            .size(MARK_SIZE)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.initialGrapheme(),
            style = TitanTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * `8 ex · ~55m`, or just `8 ex` for a template there is nothing to pace.
 *
 * The estimate is dropped rather than shown as `~0m` when the template lays out nothing —
 * which is what a template whose exercises were deleted underneath it looks like. A row
 * reading "0 ex · ~0m" states the same absence twice and the second one reads as a
 * measurement.
 */
@Composable
private fun TemplateSummary.meta(): String {
    val count = pluralStringResource(R.plurals.template_exercise_count, exerciseCount, exerciseCount)

    return if (exerciseCount == 0) {
        count
    } else {
        stringResource(R.string.template_meta, count, estimatedDuration.formatAsEstimate())
    }
}

/** The whole row as one sentence. See the note on [TemplateRow]. */
@Composable
private fun TemplateSummary.spokenDescription(): String {
    val count = pluralStringResource(
        R.plurals.template_exercise_count_description,
        exerciseCount,
        exerciseCount,
    )
    val minutes = estimatedDuration.inWholeMinutes.toInt()
    val body = if (exerciseCount == 0) {
        count
    } else {
        pluralStringResource(
            R.plurals.template_description_duration,
            minutes,
            count,
            minutes,
        )
    }

    return when (val category = template.category) {
        null -> stringResource(R.string.template_description, template.name, body)

        else -> stringResource(
            R.string.template_description_category,
            template.name,
            body,
            category.label(),
        )
    }
}

/**
 * The mark's edge, matching the wireframe. Not a scale step: it is a fixed square sized so
 * the letter inside it reads at the `numeric` role, and it is used here only.
 */
private val MARK_SIZE = 36.dp

/**
 * A quick-start card's width, as a range.
 *
 * The minimum is wide enough for two words of a template name at the body scale and narrow
 * enough that the card after next is cut off by the edge of a 360dp screen — which is what says
 * the strip scrolls without a chevron having to. The maximum is what stops a long name and a
 * large font scale between them turning one card into the whole screen.
 */
private val CARD_WIDTH_MIN = 140.dp
private val CARD_WIDTH_MAX = 240.dp

/** Material's own figure for disabled content — see [TemplateCard]'s `enabled`. */
private const val DISABLED_ALPHA = 0.38f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Template row", widthDp = 360)
@Composable
fun TemplateRowPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TemplateRowCatalogue()
    }
}

@Preview(name = "Template row, dark", widthDp = 360)
@Composable
fun TemplateRowDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        TemplateRowCatalogue()
    }
}

@Composable
private fun TemplateRowCatalogue() {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        TemplateRow(
            previewSummary(
                name = "Push Day · Upper",
                exerciseCount = 8,
                duration = 55.minutes,
                category = TemplateCategory.STRENGTH,
            ),
        )
        HairlineDivider()
        TemplateRow(
            previewSummary(
                name = "Leg Day · Heavy",
                exerciseCount = 6,
                duration = 62.minutes,
                category = TemplateCategory.HYPERTROPHY,
            ),
        )
        HairlineDivider()
        TemplateRow(
            previewSummary(
                name = "HIIT Cardio",
                exerciseCount = 3,
                duration = 28.minutes,
                category = TemplateCategory.ENDURANCE,
            ),
        )
        HairlineDivider()
        // Uncategorised, and long enough to need the name's ellipsis.
        TemplateRow(
            previewSummary(
                name = "Saturday morning full body and conditioning",
                exerciseCount = 12,
                duration = 95.minutes,
            ),
        )
        HairlineDivider()
        // The template whose exercises were removed underneath it.
        TemplateRow(
            previewSummary(
                name = "Emptied out",
                exerciseCount = 0,
                duration = Duration.ZERO,
                category = TemplateCategory.MOBILITY,
            ),
        )
    }
}

private fun previewSummary(
    name: String,
    exerciseCount: Int,
    duration: Duration,
    category: TemplateCategory? = null,
) = TemplateSummary(
    template = WorkoutTemplate(id = name.hashCode().toLong(), name = name, category = category),
    exerciseCount = exerciseCount,
    estimatedDuration = duration,
)

@Preview(name = "Template card", widthDp = 360)
@Composable
fun TemplateCardPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TemplateCardStrip()
    }
}

@Preview(name = "Template card, dark", widthDp = 360)
@Composable
fun TemplateCardDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        TemplateCardStrip()
    }
}

/** The strip as Home lays it out: three cards, the third one cut off by the screen's edge. */
@Composable
private fun TemplateCardStrip() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        TemplateCard(
            previewSummary(name = "Push Day", exerciseCount = 8, duration = 55.minutes),
            onClick = {},
        )
        TemplateCard(
            previewSummary(name = "Pull Day", exerciseCount = 7, duration = 50.minutes),
            onClick = {},
        )
        TemplateCard(
            previewSummary(
                name = "Saturday morning full body",
                exerciseCount = 12,
                duration = 95.minutes,
            ),
            onClick = {},
        )
    }
}
