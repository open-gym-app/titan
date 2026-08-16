package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.RpeSelector
import com.opengym.core.ui.component.SetLogRow
import com.opengym.core.ui.component.SetTypeSelector
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.active.ActiveSessionEvent
import com.opengym.feature.session.active.SessionExerciseRow
import com.opengym.feature.session.active.SetEntry
import com.opengym.feature.session.active.SetEntryError

/**
 * One exercise of the workout: everything logged against it, and the row for the next set.
 *
 * Every exercise gets one of these. There is no "open" exercise and no collapsed variant — see
 * [com.opengym.feature.session.active.ActiveSessionUiState] for why the concept went — so what
 * distinguishes one card from another is what is in it, not which one the user last tapped.
 *
 * ### Where the accent is spent
 *
 * On the row being typed and its tick, and on nothing else. Every card is neutral, because with
 * the whole workout on the page an accented *card* would either mark all of them or reintroduce
 * a selection by the back door. What a glance from arm's length needs to find is the box the
 * next number goes in, and that box is accented on each card.
 *
 * ### The controls under the row appear when the set does
 *
 * The set-type chips and the note affordance are drawn once the entry has been started — see
 * [SetEntry.isStarted]. Not a fold: nothing logged is ever hidden, and both controls are for a
 * set that is being written rather than for one that has been. Drawing them under every exercise
 * of an eight-exercise workout puts three rows of chrome between each pair of set tables, and
 * the thing that suffers is reading the workout, which is the whole reason the page is like this.
 *
 * @param row the exercise, its sets and its half-typed set.
 * @param enabled false makes the whole card read-only — a paused session, or one being closed
 *   out. The fields keep what they hold, because they are still the user's copy of it.
 * @param showRpeScale whether this card's rating scale is open under the table. Hoisted rather
 *   than remembered here so that only one is ever open on the screen.
 *
 *   It is drawn *inline* rather than in a bottom sheet, which is the one layout decision here
 *   worth stating. A sheet is the obvious home for a nine-cell grid and it puts the grid over
 *   the row it is rating — so the set the user is looking at, and the last two they did, are
 *   behind it at the moment they are deciding how hard the last one felt.
 * @param onRpeToggle open or close that scale.
 * @param onRemove cut this exercise out of the workout — the machine is taken, or it was a
 *   mistake. Refused once sets have been logged against it, which the screen reports rather than
 *   confirms away; see `RemoveSessionExerciseUseCase`.
 */
@Composable
fun SessionExerciseCard(
    row: SessionExerciseRow,
    onEvent: (ActiveSessionEvent) -> Unit,
    onRpeToggle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    showRpeScale: Boolean = false,
    enabled: Boolean = true,
    isLogging: Boolean = false,
) {
    val entry = row.entry

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            ExerciseHeading(row = row, onRemove = onRemove, enabled = enabled)

            SetTable(row = row, onEvent = onEvent, onRpeClick = onRpeToggle, enabled = enabled)

            if (showRpeScale) {
                RpeSelector(
                    rpe = entry.rpe,
                    onRpeChange = {
                        onEvent(ActiveSessionEvent.RpeChanged(row.sessionExerciseId, it))
                        onRpeToggle()
                    },
                )
            }

            if (entry.isStarted) {
                SetTypeSelector(
                    selected = entry.setType,
                    onSelect = {
                        onEvent(ActiveSessionEvent.SetTypeChanged(row.sessionExerciseId, it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                SetNote(
                    sessionExerciseId = row.sessionExerciseId,
                    notes = entry.notes,
                    onEvent = onEvent,
                    enabled = enabled,
                )
            }

            Button(
                onClick = { onEvent(ActiveSessionEvent.SetLogged(row.sessionExerciseId)) },
                enabled = enabled && entry.canLog && !isLogging,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.padding(end = OpenGymTheme.spacing.sm),
                )
                Text(
                    text = stringResource(R.string.active_complete_set, row.nextSetNumber),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The name, its place in the workout, and the way out of it. */
@Composable
private fun ExerciseHeading(row: SessionExerciseRow, onRemove: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Eyebrow(
                text = stringResource(R.string.active_exercise_position, row.position + 1),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.exercise?.name ?: stringResource(R.string.active_exercise_removed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = OpenGymTheme.spacing.xs),
            )
        }
        if (row.supersetLabel != null) {
            OpenGymBadge(
                text = stringResource(R.string.active_superset, row.supersetLabel),
                tone = BadgeTone.ACCENT,
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(
                    R.string.active_remove_exercise,
                    row.exercise?.name ?: stringResource(R.string.active_exercise_removed),
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The sets logged so far, with the entry row under them.
 *
 * The logged half is [LoggedSetTable], the same one every exercise on the screen draws. What is
 * added here is the row being typed, which every exercise also has: a superset is logged by
 * alternating between its members, and a row that only existed on one of them would make that
 * impossible.
 */
@Composable
private fun SetTable(
    row: SessionExerciseRow,
    onEvent: (ActiveSessionEvent) -> Unit,
    onRpeClick: () -> Unit,
    enabled: Boolean,
) {
    val entry = row.entry
    val error = entry.error?.message()

    Column(modifier = Modifier.fillMaxWidth()) {
        LoggedSetTable(sets = row.sets)
        HairlineDivider()

        SetLogRow(
            setNumber = row.nextSetNumber,
            weight = entry.weight,
            reps = entry.reps,
            onWeightChange = {
                onEvent(ActiveSessionEvent.WeightChanged(row.sessionExerciseId, it))
            },
            onRepsChange = { onEvent(ActiveSessionEvent.RepsChanged(row.sessionExerciseId, it)) },
            // The row's own tick and the button under the table are the same action. Both are
            // here because they answer different reaches: the tick is where a lifter's thumb
            // already is after typing the reps, and the button is what the eye finds.
            onCompletedChange = {
                if (it) onEvent(ActiveSessionEvent.SetLogged(row.sessionExerciseId))
            },
            setType = entry.setType,
            rpe = entry.rpe,
            onRpeClick = onRpeClick,
            // #101 fills this. The column is drawn either way, so adding it later does not move
            // every figure in the table sideways.
            previous = null,
            isActive = true,
            error = error,
            enabled = enabled,
        )
    }
}

/**
 * The per-set note: an affordance until it is opened, then a field.
 *
 * Closed by clearing it, which is why there is no cancel — an empty note and no note are the
 * same set, so the field's own empty state is the way out.
 */
@Composable
private fun SetNote(
    sessionExerciseId: Long,
    notes: String?,
    onEvent: (ActiveSessionEvent) -> Unit,
    enabled: Boolean,
) {
    if (notes == null) {
        TextButton(
            onClick = { onEvent(ActiveSessionEvent.NotesChanged(sessionExerciseId, "")) },
            enabled = enabled,
        ) {
            Text(
                text = stringResource(R.string.active_add_note),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        return
    }

    OutlinedTextField(
        value = notes,
        onValueChange = { onEvent(ActiveSessionEvent.NotesChanged(sessionExerciseId, it)) },
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.extraSmall,
        textStyle = MaterialTheme.typography.bodyMedium,
        label = { Text(text = stringResource(R.string.active_note_label)) },
        trailingIcon = {
            TextButton(
                onClick = { onEvent(ActiveSessionEvent.NotesChanged(sessionExerciseId, null)) },
                enabled = enabled,
            ) {
                Text(text = stringResource(R.string.active_note_clear))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The refusal, in words.
 *
 * Two of the three are this screen's own and come out of its resources; the third is
 * `SetValidator`'s, which already names the field, the bound and the value it saw. Paraphrasing
 * that one would be a second copy of a rule that lives in the domain.
 */
@Composable
private fun SetEntryError.message(): String = when (this) {
    SetEntryError.WeightNotANumber -> stringResource(R.string.active_weight_not_a_number)
    SetEntryError.RepsNotANumber -> stringResource(R.string.active_reps_not_a_number)
    is SetEntryError.Refused -> reason
}
