package com.titan.feature.progression.stats.component

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.MuscleTraining
import com.titan.core.ui.component.BodyDiagram
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.displayName
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R

/**
 * What the period actually trained: the figures with the worked muscles lit, and the ranking
 * beside them.
 *
 * ### Both halves, as everywhere else this pair appears
 *
 * The diagram makes `Middle back` mean something to somebody who has not learned the names,
 * and the list makes it exact — the illustration has no shape for a handful of the seeded
 * groups, and for those the written row is the only place they appear. The same argument
 * `MusclesWorkedCard` makes on the exercise detail screen, and the same two tones on both
 * sides so the picture and the list cannot disagree about what was worked directly.
 *
 * ### Ranked by sets, and only the top of it
 *
 * Sets rather than volume, because the map answers "what have I been training" and volume
 * would put one heavy compound above a whole session of accessory work — see [MuscleTraining].
 * A quarter's training touches a dozen groups and a card with twelve rows in it is a table, so
 * the list is cut at [VISIBLE_MUSCLES] and says how many it left out. The cut is here rather
 * than in the ViewModel because it is a fact about how much room this card has.
 *
 * ### Not a share of anything
 *
 * There are no bars and no percentages. `MuscleContributionList` draws a muscle against the
 * *top* muscle of a session, which is a comparison the attribution supports; a proportion of
 * the total is one it does not — every secondary link takes a full half regardless of how many
 * there are, so the figures sum to more than the training that produced them. A count of sets
 * beside a name claims nothing it cannot back up.
 */
@Composable
fun MuscleMapCard(
    muscles: List<MuscleTraining>,
    modifier: Modifier = Modifier,
) {
    val ranked = muscles.take(VISIBLE_MUSCLES)
    val remaining = muscles.size - ranked.size

    TitanCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(TitanTheme.spacing.card),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both views, because a period's training is never one side of the body: a week of
            // pull work is lats and biceps, and the lats are a back-view shape. Shorter than
            // the component's own default so the two figures and the ranking fit across 360dp.
            BodyDiagram(
                primary = muscles.filter(MuscleTraining::isPrimary).map(MuscleTraining::muscle),
                secondary = muscles.filterNot(MuscleTraining::isPrimary).map(MuscleTraining::muscle),
                height = DIAGRAM_HEIGHT,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
            ) {
                ranked.forEach { muscle -> MuscleRankRow(muscle = muscle) }
                if (remaining > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.stats_muscles_more, remaining, remaining),
                        style = TitanTheme.typography.numericSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * One muscle: `Chest` on the left, `18 sets` on the right.
 *
 * Announced as a single node. A name and a count as two nodes are read as two unrelated
 * fragments, and the weight that distinguishes a prime mover from an assistor is invisible to
 * a screen reader — so the description says which it was, exactly as `MuscleContributionRow`
 * does for its two tones.
 */
@Composable
private fun MuscleRankRow(
    muscle: MuscleTraining,
    modifier: Modifier = Modifier,
) {
    val name = muscle.muscle.displayName()
    val sets = pluralStringResource(R.plurals.stats_muscle_sets, muscle.sets, muscle.sets)
    val spoken = stringResource(
        if (muscle.isPrimary) {
            R.string.stats_muscle_description_primary
        } else {
            R.string.stats_muscle_description_secondary
        },
        name,
        sets,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            // The groups worked directly are the ones the diagram paints in the accent. Weight
            // rather than colour, because the accent is already doing that job in the picture
            // beside this and a second use of it in the list would be two accents on one card.
            fontWeight = if (muscle.isPrimary) FontWeight.Medium else FontWeight.Normal,
            color = if (muscle.isPrimary) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = sets,
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}

/**
 * How many muscles the ranking shows before it starts counting the rest.
 *
 * Six, which is what fits beside a 150dp figure without the card growing taller than the
 * diagram it is drawn against, and about as many as anybody reads off a list they are
 * scanning. The wireframe draws six.
 */
private const val VISIBLE_MUSCLES = 6

/** Short enough that two figures and a six-row ranking fit across 360dp. */
private val DIAGRAM_HEIGHT = 150.dp
