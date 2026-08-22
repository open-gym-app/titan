package com.opengym.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * One choice in a list where exactly one is taken, drawn as a card rather than as a row.
 *
 * The wireframes' onboarding treatment: a bordered box per option, a radio on the left, the
 * name and one line saying what it means beside it, and the whole box carrying the accent
 * once it is the chosen one. It is what a question asked *as a screen* looks like, where
 * `ChoiceDialog`'s plain radio rows are what the same question looks like squeezed into a
 * dialog — same semantics, four times the target, and room for the detail line that is the
 * whole reason a beginner can answer "strength or hypertrophy?" at all.
 *
 * ## Selection is a colour, not a weight
 *
 * The wireframes thicken the chosen option's border to 2dp. This does not: the border stays
 * at [HAIRLINE_WIDTH] like every other line in the app — see `Hairline.kt`, which is explicit
 * that weight is not a call site's to choose — and selection is carried by the accent border
 * over the accent-soft fill instead. Two line weights on one screen read as a mistake rather
 * than as a hierarchy, and a border that grows on tap nudges the text inside it by a pixel,
 * which is the kind of movement that makes a list of options feel unstable under the thumb.
 *
 * ## Accessibility
 *
 * The *whole card* is the [selectable], with [Role.RadioButton] and a radio that has no
 * handler of its own. That is both the larger target and what makes a screen reader announce
 * "Hypertrophy, volume and time under tension, radio button, selected" rather than reading a
 * control and some unrelated text that happens to sit next to it. Callers should wrap the
 * list in `Modifier.selectableGroup()` so the set is announced as one.
 *
 * @param detail the line under the name — what the option actually means. Optional, and worth
 *   writing wherever the name alone is a guess.
 */
@Composable
fun SelectableOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = HAIRLINE_WIDTH,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            // `onClick = null`: the card above owns the click and the semantics. A radio with
            // its own handler here would be a second, smaller target announcing the same thing.
            RadioButton(selected = selected, onClick = null)

            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (detail != null) {
                    // Mono, like every other supporting line the wireframes set under a name.
                    // It is not a number, but it is *metadata* — the same role the meta line
                    // under an exercise name and under a session row is drawn in.
                    Text(
                        text = detail,
                        style = OpenGymTheme.typography.numericSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
