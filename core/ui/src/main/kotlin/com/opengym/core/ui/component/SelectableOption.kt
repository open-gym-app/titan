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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * One choice in a list where exactly one is taken, drawn as a card rather than as a row.
 *
 * The wireframes' onboarding treatment: a bordered box per option, the name and one line
 * saying what it means, and the whole box carrying the accent once it is the chosen one. It is
 * what a question asked *as a screen* looks like, where `ChoiceDialog`'s plain radio rows are
 * what the same question looks like squeezed into a dialog — same semantics, four times the
 * target, and room for the detail line that is the whole reason a beginner can answer
 * "strength or hypertrophy?" at all.
 *
 * ## The mark leads, the radio follows
 *
 * An [icon] sits at the start and the radio at the end, which is the opposite of a settings
 * row and deliberate. A list of options is *scanned* before it is chosen from, and the thing
 * worth scanning is what each option is — four identical radios stacked down the left edge
 * are four identical marks the eye has to read past. Putting the state at the end also puts it
 * where the reader's eye arrives last, which is when the answer to "and which one is on?"
 * matters.
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
 * "Selected, Hypertrophy, volume and time under tension, radio button" rather than reading a
 * control and some unrelated text that happens to sit next to it. Callers should wrap the
 * list in `Modifier.selectableGroup()` so the set is announced as one.
 *
 * The state is said in the app's own words — `stateDescription`, "Selected" or "Not selected"
 * — rather than left to what a screen reader infers from the `selected` flag, which varies by
 * reader and version and is sometimes nothing at all for a radio that is off. Stated, it is
 * the same two words on every card, and a test can assert them.
 *
 * @param detail the line under the name — what the option actually means. Optional, and worth
 *   writing wherever the name alone is a guess. Set in prose rather than in the mono numeric
 *   role: it is a sentence about the option, and the mono faces in this app mean *figures*.
 * @param icon the mark for this option. Optional — a list whose options have no natural
 *   picture is better with none than with four shrugs — but where there is one it is what
 *   makes the list scannable, and it must be given for *every* option in the group or the
 *   ones without it read as disabled.
 */
@Composable
fun SelectableOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val state = stringResource(if (selected) R.string.option_selected else R.string.option_not_selected)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = state },
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
            if (icon != null) {
                IconTile(
                    icon = icon,
                    // The chosen card is already filled with the accent-soft shade, so a tile
                    // in that same colour would vanish into it. It takes the surface behind
                    // the card instead, which keeps the mark a distinct object on both.
                    container = if (selected) {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // `onClick = null`: the card above owns the click and the semantics. A radio with
            // its own handler here would be a second, smaller target announcing the same thing.
            RadioButton(selected = selected, onClick = null)
        }
    }
}
