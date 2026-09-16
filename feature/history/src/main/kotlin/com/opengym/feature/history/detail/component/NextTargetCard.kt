package com.opengym.feature.history.detail.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.opengym.core.domain.model.LoadSuggestion
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.reasonText
import com.opengym.core.ui.format.formatSet
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R

/**
 * What the next set of this exercise should be, and where that came from (#M7-16).
 *
 * The rest of the detail screen is about what the user has *done* — the bests, the how-to,
 * their own notes. This is the one card about what to do next: the next-load engine's
 * prescription in the hero figure role, the rule in a sentence under it, and under a rule the
 * working sets it was read from. The sets are the part a reader checks the answer against —
 * "82.5 × 8 because 80 × 6 three times" is a suggestion a lifter can agree or disagree with,
 * where "82.5 × 8" alone is one they have to take on trust.
 *
 * ### Absent, not empty
 *
 * The caller draws nothing for a null suggestion — an exercise never performed, timed work,
 * or one whose only history is warm-ups. A card reading "no target yet" would be the screen
 * telling the user what they have not done, which is the call `PersonalRecordsCard` makes on
 * the same page.
 *
 * ### Read as one sentence
 *
 * The card is a single node: the target, the reason, then the sets. Read as fragments it would
 * be a figure, a sentence and a run of `80 kg × 6` with nothing saying which of the three is
 * the answer and which the working. The parts are the same functions the set row's
 * `SetTargetLine` speaks through, so the target heard here and the one heard mid-workout are
 * the same words.
 *
 * Nothing here is a button. The screen this sits on has no fields to fill; the tap-to-fill
 * belongs to the entry row, where the figures have somewhere to go.
 */
@Composable
internal fun NextTargetCard(
    target: LoadSuggestion,
    modifier: Modifier = Modifier,
) {
    // The figures without the "Target" the set row prefixes them with: the section heading
    // over this card already says what they are.
    val figures = formatSet(target.weight, target.reps) ?: target.reps.toString()
    val reason = target.reasonText()
    val separator = stringResource(R.string.detail_next_target_basis_separator)
    val basis = target.basis.sets
        .mapNotNull { set -> formatSet(set.weight, set.reps) }
        .joinToString(separator)
    val spoken = stringResource(R.string.detail_next_target_description, figures, reason, basis)

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            // Replaced rather than merged: an eyebrow inside a merged node speaks over it —
            // see `Eyebrow` — and the sentence above already says what the eyebrow says.
            modifier = Modifier
                .padding(OpenGymTheme.spacing.card)
                .clearAndSetSemantics { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            // The figure alone, in the hero role. The set row's `· +2.5 kg` is not repeated
            // beside it: at 32sp a mono `82.5 kg × 3` is most of a 320dp card already, and the
            // reason under it quotes the step wherever there is one.
            Text(
                text = figures,
                style = OpenGymTheme.typography.numericLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HairlineDivider(modifier = Modifier.padding(vertical = OpenGymTheme.spacing.xs))

            Eyebrow(text = stringResource(R.string.detail_next_target_basis))
            // One line that wraps, rather than a table: these sets are the working shown, not
            // a log to be corrected, and a set table on a reading screen invites typing.
            Text(
                text = basis,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
