package com.opengym.feature.session.active.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.format.formatSet
import com.opengym.core.ui.theme.MIN_TOUCH_TARGET
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.active.MobilityCard
import com.opengym.feature.session.active.MobilityKind
import com.opengym.feature.session.active.MobilityMovement

/**
 * The warm-up above the workout, or the cool-down under it (#214).
 *
 * One composable for both, because they are one card: a heading, a list of movements, and a
 * single action that puts them into the session. Only the words and the ramp-up differ, and a
 * second composable for those would be two layouts to keep looking the same.
 *
 * ### It proposes and does not act
 *
 * Everything drawn here is a suggestion until the button is pressed — see [MobilityCard]. That
 * is why the movements are *listed* rather than counted: the user is agreeing to these
 * exercises, so the card has to say which. Once they are in the workout it folds to its heading
 * with `Added` beside it, because the workout below is now the better copy of the same list.
 *
 * ### Why the whole heading row is the fold
 *
 * A chevron alone is a 24dp target, and the row it sits in is the thing a thumb aims at anyway.
 * Making the row the control gives it the full width and [MIN_TOUCH_TARGET] of height without
 * the chevron having to claim space it does not draw — see `TouchTarget` on why the reservation
 * has to be layout. The chevron stays as the affordance and is not itself clickable.
 *
 * ### Under TalkBack
 *
 * The heading is marked as one, so heading navigation lands on it. The fold is announced as a
 * button with an expanded/collapsed state rather than as two different labels, which is what
 * lets a reader tell the state from the action. The movements are plain text — a list of names
 * with no actions on them — and the two buttons at the foot are the only other stops.
 *
 * @param card what to draw, or the caller draws nothing.
 * @param kind which of the two this is: the heading, and whether a ramp-up is possible.
 * @param onExpand fold open or shut.
 * @param onAdd put the suggestion into the workout.
 * @param onDismiss take the card off this workout's screen.
 * @param enabled false leaves the card readable and its actions inert — the workout is paused
 *   or on its way out, and `AddSessionExercisesUseCase` refuses both.
 */
@Composable
internal fun MobilitySuggestionCard(
    card: MobilityCard,
    kind: MobilityKind,
    onExpand: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        FoldHeader(card = card, kind = kind, onExpand = onExpand)

        AnimatedVisibility(visible = card.isExpanded) {
            Column {
                HairlineDivider()
                Column(
                    modifier = Modifier.padding(OpenGymTheme.spacing.card),
                    verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
                ) {
                    Movements(card = card)
                    if (card.rampUp.isNotEmpty()) RampUp(card = card)
                    if (!card.isAdded) {
                        Actions(card = card, onAdd = onAdd, onDismiss = onDismiss, enabled = enabled)
                    }
                }
            }
        }
    }
}

/** The row that folds the card, and says which card it is and whether it has been taken. */
@Composable
private fun FoldHeader(
    card: MobilityCard,
    kind: MobilityKind,
    onExpand: (Boolean) -> Unit,
) {
    val expanded = stringResource(R.string.active_mobility_expanded)
    val collapsed = stringResource(R.string.active_mobility_collapsed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                // The label is the action; the state is the state. Announced separately so a
                // reader hears "Warm-up, collapsed, button" rather than two labels that swap.
                onClickLabel = stringResource(R.string.active_mobility_fold),
                role = Role.Button,
                onClick = { onExpand(!card.isExpanded) },
            )
            .semantics { stateDescription = if (card.isExpanded) expanded else collapsed }
            .heightIn(min = MIN_TOUCH_TARGET)
            .padding(horizontal = OpenGymTheme.spacing.card),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Eyebrow(
            text = stringResource(kind.titleRes()),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (card.isAdded) {
            Text(
                text = stringResource(R.string.active_mobility_added),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            imageVector = if (card.isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            // The row speaks for itself; a description here would be announced twice.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The movements themselves, and the line about easy cardio where there is one. */
@Composable
private fun Movements(card: MobilityCard) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm)) {
        card.movements.forEach { movement ->
            Text(
                text = movement.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (card.includeGeneralNote) {
            Text(
                text = stringResource(R.string.active_warmup_general_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The ramp-up sets, under the name of the lift they are for.
 *
 * Drawn as lines rather than as a set table: these are not rows anybody can type into yet — they
 * are what *will* be written if the card is taken — and the table under an exercise card is
 * where they become editable.
 */
@Composable
private fun RampUp(card: MobilityCard) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm)) {
        Text(
            text = stringResource(R.string.active_warmup_ramp_up, card.rampUpExerciseName.orEmpty()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        card.rampUp.forEach { set ->
            // Null only for a planned set with neither figure, which the planner never builds —
            // drawn as nothing rather than as a dash, since there is no column to keep aligned.
            val line = formatSet(weightMilligrams = set.weight, reps = set.reps) ?: return@forEach
            Text(
                text = line,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Take it, or send it away. */
@Composable
private fun Actions(
    card: MobilityCard,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onDismiss,
            enabled = enabled && !card.isAdding,
            modifier = Modifier.weight(1f),
        ) {
            ActionLabel(stringResource(R.string.active_mobility_dismiss))
        }
        Button(
            onClick = onAdd,
            enabled = enabled && card.canAdd,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.weight(1f),
        ) {
            ActionLabel(stringResource(R.string.active_mobility_add))
        }
    }
}

/**
 * A button's label, allowed to wrap.
 *
 * No `maxLines`, unlike most of this app's buttons. Two actions side by side leave each of them
 * about 150dp, and "Add to session" at twice the system font size is wider than that — in
 * French and Portuguese it is wider than that at *one*. A clipped verb is a button whose
 * meaning is the part that did not fit, so the button grows instead. Centred, because a
 * second line that started under the first would not read as one label.
 */
@Composable
private fun ActionLabel(text: String) {
    Text(text = text, textAlign = TextAlign.Center)
}

/** What the card is called. The one thing that differs between the two beyond the ramp-up. */
private fun MobilityKind.titleRes(): Int =
    when (this) {
        MobilityKind.WARMUP -> R.string.active_warmup_title
        MobilityKind.COOLDOWN -> R.string.active_cooldown_title
    }

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Warm-up card", showBackground = true, widthDp = 360)
@Composable
fun MobilitySuggestionCardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MobilitySuggestionCard(
            card = previewWarmup(),
            kind = MobilityKind.WARMUP,
            onExpand = {},
            onAdd = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Warm-up card, dark", showBackground = true, widthDp = 360)
@Composable
fun MobilitySuggestionCardDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        MobilitySuggestionCard(
            card = previewWarmup(),
            kind = MobilityKind.WARMUP,
            onExpand = {},
            onAdd = {},
            onDismiss = {},
        )
    }
}

/** Taken and folded away, which is every card's resting state once the workout has it. */
@Preview(name = "Cool-down card, added", showBackground = true, widthDp = 360)
@Composable
fun MobilitySuggestionCardAddedPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MobilitySuggestionCard(
            card = MobilityCard(
                movements = listOf(
                    MobilityMovement(exerciseId = 10L, name = "Standing chest stretch"),
                    MobilityMovement(exerciseId = 11L, name = "Overhead triceps stretch"),
                ),
                isExpanded = false,
                isAdded = true,
            ),
            kind = MobilityKind.COOLDOWN,
            onExpand = {},
            onAdd = {},
            onDismiss = {},
        )
    }
}

/** Every label at twice its size — the bar #40 holds every screen to. */
@Preview(name = "Warm-up card - twice the font", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
fun MobilitySuggestionCardLargeFontPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MobilitySuggestionCard(
            card = previewWarmup(),
            kind = MobilityKind.WARMUP,
            onExpand = {},
            onAdd = {},
            onDismiss = {},
        )
    }
}

/** A push day's warm-up: two movements, the cardio note, and an 80 kg bench to ramp into. */
private fun previewWarmup(): MobilityCard =
    MobilityCard(
        movements = listOf(
            MobilityMovement(exerciseId = 1L, name = "Arm circles"),
            MobilityMovement(exerciseId = 2L, name = "Band pull-apart"),
            MobilityMovement(exerciseId = 3L, name = "Scapular push-up"),
        ),
        rampUp = listOf(
            PlannedSet(setType = SetType.WARMUP, reps = 5, weight = 40_000_000L),
            PlannedSet(setType = SetType.WARMUP, reps = 3, weight = 60_000_000L),
        ),
        rampUpExerciseName = "Bench press",
        includeGeneralNote = true,
    )
