package com.titan.feature.session.summary.component

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
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.ui.component.BodyDiagram
import com.titan.core.ui.component.MuscleContribution
import com.titan.core.ui.component.MuscleContributionList
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R

/**
 * The figures with the worked muscles lit up, and the same session in bars beside them.
 *
 * Both halves, for the reason the exercise detail's own muscles card gives: the illustration
 * is what makes "middle back" mean something to somebody who has not learned the names, and
 * it has no shape at all for a handful of the seeded groups — for those, the written row is
 * the only place they appear. Neither half is decoration for the other.
 *
 * What is *different* here is what the written half says. The exercise detail lists names
 * under a `PRIMARY` / `SECONDARY` badge, because an exercise's muscles are a fixed fact about
 * the movement. A session's are a quantity: this chest took twice what these triceps did, and
 * that is what a bar carries and a name cannot. The two tones still line up with the diagram's
 * — accent for a prime mover, its container tone for an assistor — so the picture and the list
 * agree at a glance.
 *
 * The caller draws nothing when there are no contributions. An empty body with an empty legend
 * is a section that looks like it failed to load.
 */
@Composable
internal fun SessionMusclesCard(
    contributions: List<MuscleContribution>,
    primary: List<MuscleGroup>,
    secondary: List<MuscleGroup>,
    modifier: Modifier = Modifier,
) {
    val shown = contributions.take(MAX_BARS)
    val hidden = contributions.size - shown.size

    TitanCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(TitanTheme.spacing.card),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both views, because a session's muscles routinely span them — a push day is
            // chest and triceps, and the triceps is a back-view shape. Shorter than the
            // component's own default so two figures and a column of bars fit across 360dp
            // without the muscle names wrapping.
            BodyDiagram(
                primary = primary,
                secondary = secondary,
                height = DIAGRAM_HEIGHT,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
            ) {
                MuscleContributionList(contributions = shown)
                if (hidden > 0) {
                    Text(
                        text = stringResource(R.string.summary_muscles_more, hidden),
                        style = TitanTheme.typography.numericSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * How many bars the card draws before it summarises the rest.
 *
 * A compound leg day attributes volume to a dozen groups, and twelve bars is a table rather
 * than a readout — the muscles that took the least are also the ones the reader learns least
 * from, so the tail is counted instead of drawn. The stat tile above still counts all of them,
 * which is what makes `+3 more` legible rather than a discrepancy.
 */
private const val MAX_BARS = 6

/**
 * Shorter than `BodyDiagramDefaults.HEIGHT`, which is sized for a figure standing alone. Two
 * of them plus a column of bars have to share 360dp here.
 */
private val DIAGRAM_HEIGHT = 112.dp
