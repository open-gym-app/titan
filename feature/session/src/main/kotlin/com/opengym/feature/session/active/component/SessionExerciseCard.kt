package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.DragHandle
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
 * @param position where it sits in the workout, from 0 — the *drawn* order rather than
 *   [SessionExerciseRow.position], which is what is stored and is a frame behind it while a
 *   reorder is being written.
 * @param enabled false makes the whole card read-only — a paused session, or one being closed
 *   out. The fields keep what they hold, because they are still the user's copy of it.
 *
 *   Every control takes it, the chips and the rating grid included. Those two are the ones it is
 *   easiest to leave out — they are not fields and they are not the tick — and leaving them live
 *   is a session that says it is suspended while its set type is still being changed, which is
 *   the read-only guarantee `LogSetUseCase` enforces on the write path being broken on this one.
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
 * @param onSwap do a different exercise in this slot, keeping its place in the running order and
 *   whatever it is paired with. The picker is what answers; the refusal it can meet is the same
 *   one [onRemove] can, and for a related reason — see `SwapSessionExerciseUseCase`.
 * @param isMovable whether holding this card would pick it up, which is the only thing that
 *   draws the grab glyph in front of its position — see [ExerciseHeading]. The gesture has
 *   nothing else on the screen announcing it, and the one thing worse than an unannounced
 *   gesture is one announced where it does not work.
 */
@Composable
fun SessionExerciseCard(
    row: SessionExerciseRow,
    position: Int,
    onEvent: (ActiveSessionEvent) -> Unit,
    onRpeToggle: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
    showRpeScale: Boolean = false,
    enabled: Boolean = true,
    isLogging: Boolean = false,
    isMovable: Boolean = false,
) {
    val entry = row.entry

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            ExerciseHeading(
                row = row,
                position = position,
                onRemove = onRemove,
                onSwap = onSwap,
                enabled = enabled,
                isMovable = isMovable,
            )

            SetTable(row = row, onEvent = onEvent, onRpeClick = onRpeToggle, enabled = enabled)

            // Said once, under the table, for an exercise this screen cannot log at all — see
            // [SessionExerciseRow.isLoggedByDuration]. Not an entry error: nothing the user
            // typed is wrong, and the row it would sit under is not the row at fault.
            if (row.isLoggedByDuration) {
                Text(
                    text = stringResource(R.string.active_logged_by_duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showRpeScale) {
                RpeSelector(
                    rpe = entry.rpe,
                    onRpeChange = {
                        onEvent(ActiveSessionEvent.RpeChanged(row.sessionExerciseId, it))
                        onRpeToggle()
                    },
                    // The scale can be open *across* a pause — the tap that opens it is what
                    // `SetLogRow` withholds, not the one that picks a value out of it — so a
                    // read-only card has to say so here too, or a paused session still takes
                    // ratings and saves a draft of them.
                    enabled = enabled,
                )
            }

            if (entry.isStarted) {
                SetTypeSelector(
                    selected = entry.setType,
                    onSelect = {
                        onEvent(ActiveSessionEvent.SetTypeChanged(row.sessionExerciseId, it))
                    },
                    enabled = enabled,
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
                enabled = enabled && row.canLogEntry && !isLogging,
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

/**
 * The name, its place in the workout, and the two ways to change what is in the slot.
 *
 * The grab glyph in front of the position is the only thing on the screen that says the order
 * can be changed at all. It is a *mark*, not a control — nothing happens if it is pressed, the
 * whole card responds to a hold — and it is the same glyph the row collapses to when one is
 * picked up, so the two teach each other. Drawn only where the gesture would actually work
 * ([isMovable]): a workout of one exercise, or a paused one, has nothing to reorder, and a hint
 * at a gesture that is inert is worse than no hint.
 */
@Composable
private fun ExerciseHeading(
    row: SessionExerciseRow,
    position: Int,
    onRemove: () -> Unit,
    onSwap: () -> Unit,
    enabled: Boolean,
    isMovable: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isMovable) {
                    DragHandle(
                        modifier = Modifier.size(HANDLE_SIZE),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Eyebrow(
                    text = stringResource(R.string.active_exercise_position, position + 1),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
        // Before the ×, because it is the milder of the two: swapping keeps the slot and the
        // × takes it away, and a thumb reaching past the destructive one to find the reversible
        // one is the wrong way round.
        IconButton(onClick = onSwap, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.SwapHoriz,
                contentDescription = stringResource(
                    R.string.active_swap_exercise,
                    row.exercise?.name ?: stringResource(R.string.active_exercise_removed),
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        LoggedSetTable(
            sets = row.sets,
            sessionExerciseId = row.sessionExerciseId,
            onEvent = onEvent,
            editing = row.editing,
            canSaveEdit = row.canSaveEdit,
            enabled = enabled,
        )
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
            // Not while a logged set is open above it. The tint means "this is the row you are
            // working on", and two tinted rows on one card means it does not — the correction is
            // what the user is doing, and the next set can wait for it.
            isActive = row.editing == null,
            error = error,
            enabled = enabled,
            // The fields stay live while the tick does not: a row with a weight in it and no
            // reps yet is a set half typed, not a refused one, and an exercise logged by time
            // is one this screen cannot write at all. Either way the tick would be pressed for
            // a set `LogSetUseCase` refuses.
            canComplete = row.canLogEntry,
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
 *
 * Internal because the same three refusals reach the same user two ways: under the row being
 * typed, and under the logged row reopened to correct it. `SetValidator` gives one answer about
 * a set, so the screen says it in one set of words.
 */
@Composable
internal fun SetEntryError.message(): String = when (this) {
    SetEntryError.WeightNotANumber -> stringResource(R.string.active_weight_not_a_number)
    SetEntryError.RepsNotANumber -> stringResource(R.string.active_reps_not_a_number)
    is SetEntryError.Refused -> reason
}

/**
 * The grab mark, at the size of the eyebrow it sits in front of.
 *
 * Smaller than the one on a collapsed row, which is sized against the set-row glyphs: here it is
 * a mark on a label rather than a column of its own, and it should not outweigh the word beside
 * it.
 */
private val HANDLE_SIZE: Dp = 14.dp
