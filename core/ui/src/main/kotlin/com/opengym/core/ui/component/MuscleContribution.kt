package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatVolume
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.core.ui.theme.PillShape

/**
 * One muscle's share of a session's work, as a bar reads it.
 *
 * ### The bar is relative to the hardest-worked muscle, not to the total
 *
 * This is the whole reason the type carries a [share] rather than being built from a
 * percentage at the call site. `MuscleVolumeCalculator` credits **100% of a set's volume to
 * every primary link and 50% to every secondary one**, so the figures it returns sum to more
 * than the volume that produced them — by 2x for a bench press and by 5.5x for the seed's
 * most compound movements. Its own documentation says so and forbids rendering them as a
 * share of effort: a percentage of that sum, or a pie chart of it, would state a whole that
 * does not exist.
 *
 * A bar against the *top* muscle states something the data does support — "the chest took
 * about twice what the triceps did" — and is what a lifter reads a breakdown for anyway. The
 * figure beside it is the attributed volume itself, in the user's units, so nothing on screen
 * claims to be a proportion of anything.
 *
 * @property muscle the group. Labelled through [displayName], so a seeded group reads as
 *   `Abs` and a user-authored one falls back to whatever it was called.
 * @property volume attributed volume in milligram-reps, as the calculator returns it.
 * @property share this muscle's volume over the top muscle's, in `0f..1f`. The top muscle is
 *   always 1f, which is what makes the first bar full and gives the rest something to be read
 *   against.
 * @property isPrimary whether any of the session's exercises worked it as a prime mover.
 *   Drawn in the accent; an assistor is drawn in the accent's container tone, matching
 *   [BodyDiagram]'s two levels so the picture and the list agree.
 */
@Immutable
data class MuscleContribution(
    val muscle: MuscleGroup,
    val volume: Long,
    val share: Float,
    val isPrimary: Boolean,
)

/**
 * Turn `MuscleVolumeCalculator`'s output into rows a screen can draw.
 *
 * Here rather than in each feature because the *relative* scaling is the presentation
 * decision the type documents, and a second screen deriving it a second way is how one comes
 * to draw percentages of a sum that is not a whole.
 *
 * @param volumeByMuscle the calculator's map, already ordered highest first. The order is
 *   preserved: it is the order the bars are meant to descend in, and re-sorting here would
 *   silently disagree with the calculator's own tie-breaking on name.
 * @param primaryMuscleIds the ids of the groups worked as a prime mover by *any* exercise in
 *   the window. Ids rather than [MuscleGroup]s because two reads of the same row — differing
 *   only in an icon changed since — are one muscle to everybody but `equals`.
 * @param limit how many rows to keep, longest bar first, or null for all of them. A leg day
 *   attributes volume to a dozen groups and a card with a dozen bars in it is a table; the
 *   callers that have room for one pass null.
 */
fun muscleContributions(
    volumeByMuscle: Map<MuscleGroup, Long>,
    primaryMuscleIds: Set<Long>,
    limit: Int? = null,
): List<MuscleContribution> {
    // The map is ordered highest first, so the first value is the top — no max() pass, and no
    // dependence on this function agreeing with the calculator about how ties break.
    val top = volumeByMuscle.values.firstOrNull() ?: return emptyList()
    val rows = if (limit == null) volumeByMuscle.entries else volumeByMuscle.entries.take(limit)

    return rows.map { (muscle, volume) ->
        MuscleContribution(
            muscle = muscle,
            volume = volume,
            // A window whose every set carried no load — a session of bodyweight holds — puts
            // zero on top. Every bar is then full rather than every bar being a division by
            // zero, which is the honest reading: nothing distinguishes those muscles.
            share = if (top <= 0L) 1f else (volume.toFloat() / top).coerceIn(0f, 1f),
            isPrimary = muscle.id in primaryMuscleIds,
        )
    }
}

/**
 * The muscles a session worked, as a descending stack of bars.
 *
 * The written half of the muscles-worked section — [BodyDiagram] is the picture, and neither
 * is decoration for the other. The illustration has no shape for a handful of the seeded
 * groups, and for those this list is the only place they appear at all.
 */
@Composable
fun MuscleContributionList(
    contributions: List<MuscleContribution>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        contributions.forEach { contribution ->
            MuscleContributionRow(contribution = contribution, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * One row: `Chest` and `1,850 kg` on a line, the bar under them.
 *
 * The name and the figure sit *above* the bar rather than beside it because the bar has to run
 * the full measure to be comparable with the one under it — a bar that starts after the
 * longest muscle name is a bar whose zero moves when the names change.
 *
 * Announced as one node. A name, a volume and a bar as three nodes are read out as three
 * unrelated fragments, and the bar has nothing to say to a screen reader at all — the
 * description carries whether the muscle was worked directly, which is the thing the two
 * tones encode.
 */
@Composable
fun MuscleContributionRow(
    contribution: MuscleContribution,
    modifier: Modifier = Modifier,
) {
    val name = contribution.muscle.displayName()
    val volume = formatVolume(contribution.volume)
    val spoken = stringResource(
        if (contribution.isPrimary) {
            R.string.muscle_contribution_description_primary
        } else {
            R.string.muscle_contribution_description_secondary
        },
        name,
        volume,
    )

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = volume,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        MuscleContributionBar(share = contribution.share, isPrimary = contribution.isPrimary)
    }
}

/**
 * The bar itself: a track the width of the row, filled to [share].
 *
 * Not Material's `LinearProgressIndicator`, which reads to a screen reader as something in
 * progress and draws a gap-and-stop-indicator this design has no use for. The row above
 * already carries the semantics; this is a rectangle.
 */
@Composable
private fun MuscleContributionBar(share: Float, isPrimary: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = PillShape),
    ) {
        Box(
            modifier = Modifier
                // Floored so a muscle that took a rounding error's worth of volume still draws
                // something. A row with a name, a figure and an invisible bar reads as a
                // rendering fault rather than as a small number.
                .fillMaxWidth(share.coerceAtLeast(MIN_VISIBLE_SHARE))
                .fillMaxHeight()
                .background(
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = PillShape,
                ),
        )
    }
}

/**
 * Thin enough to read as a rule rather than as a chart, which is what keeps a stack of six of
 * them from taking over the card they sit in. Not a spacing token: this is the thickness of a
 * drawn thing, not a gap between two.
 */
private val BAR_HEIGHT: Dp = 4.dp

/** Two hairlines' worth — the least that still reads as a bar. See the note at the call site. */
private const val MIN_VISIBLE_SHARE = 0.02f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private fun previewMuscle(id: Long, name: String) = MuscleGroup(id = id, name = name)

/** A push day's attributed volume, in milligram-reps, ordered as the calculator returns it. */
private const val PREVIEW_CHEST = 2_480_000_000L
private const val PREVIEW_SHOULDERS = 1_960_000_000L
private const val PREVIEW_TRICEPS = 1_240_000_000L
private const val PREVIEW_ABS = 420_000_000L

private const val PREVIEW_CHEST_ID = 1L
private const val PREVIEW_SHOULDERS_ID = 2L
private const val PREVIEW_TRICEPS_ID = 3L
private const val PREVIEW_ABS_ID = 4L

private fun previewContributions(): List<MuscleContribution> = muscleContributions(
    volumeByMuscle = linkedMapOf(
        previewMuscle(PREVIEW_CHEST_ID, "CHEST") to PREVIEW_CHEST,
        previewMuscle(PREVIEW_SHOULDERS_ID, "SHOULDERS") to PREVIEW_SHOULDERS,
        previewMuscle(PREVIEW_TRICEPS_ID, "TRICEPS") to PREVIEW_TRICEPS,
        previewMuscle(PREVIEW_ABS_ID, "ABDOMINALS") to PREVIEW_ABS,
    ),
    primaryMuscleIds = setOf(PREVIEW_CHEST_ID, PREVIEW_SHOULDERS_ID),
)

@Preview(name = "Muscle contribution", widthDp = 360)
@Composable
fun MuscleContributionListPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MuscleContributionCatalogue()
    }
}

@Preview(name = "Muscle contribution, dark", widthDp = 360)
@Composable
fun MuscleContributionListDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        MuscleContributionCatalogue()
    }
}

@Composable
private fun MuscleContributionCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        SectionHeading(text = stringResource(R.string.body_muscles_worked))
        MuscleContributionList(contributions = previewContributions())
    }
}
