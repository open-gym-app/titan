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
import com.opengym.core.ui.component.SetLogRow
import com.opengym.core.ui.component.SupersetBadge
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
 * ### Almost nothing is drawn under the row
 *
 * The set's rating and its type are cells *of* the entry row — a menu each, opened from the cell
 * that shows the answer; see `RpePicker` and `SetTypePicker`. They used to be a nine-button grid
 * and a scrolling row of chips stacked underneath it, which on an eight-exercise workout put three
 * rows of chrome between every pair of set tables, and the thing that suffered was reading the
 * workout — the whole reason the page is like this.
 *
 * What is left below the row is the note, drawn once the entry has been started — see
 * [SetEntry.isStarted]. Not a fold: nothing logged is ever hidden, and a note is prose, which is
 * the one thing a table cell cannot be.
 *
 * ### What last time is worth
 *
 * Every row carries a `PREV` cell, which is what turns the table from a log into a decision: the
 * number a lifter is choosing a weight against is the one they hit last week, and on the entry
 * row it reads as a target. It is filled per row rather than per card, because which reference a
 * row gets depends on the types of the rows above it — see [SessionExerciseRow].
 *
 * The one thing the column cannot say is that there *is* no last time. A card of dashes reads as
 * a fault rather than as a first attempt, so an exercise with no history says so once, under its
 * name, and the cells stay as they are for every other card on the screen.
 *
 * @param row the exercise, its sets and its half-typed set.
 * @param position where it sits in the workout, from 0 — the *drawn* order rather than
 *   [SessionExerciseRow.position], which is what is stored and is a frame behind it while a
 *   reorder is being written.
 * @param enabled false makes the whole card read-only — a paused session, or one being closed
 *   out. The fields keep what they hold, because they are still the user's copy of it.
 *
 *   Every control under it takes it, the rating and set-type pickers included. Those are the
 *   ones it is easiest to leave out — they are not fields and they are not the tick — and
 *   leaving them live is a session that says it is suspended while its set type is still being
 *   changed, which is the read-only guarantee `LogSetUseCase` enforces on the write path being
 *   broken on this one.
 * @param togglingSetIds the logged sets of this exercise whose tick is being written, passed
 *   through to the table so those rows' ticks go inert.
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
    onRemove: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
    togglingSetIds: Set<Long> = emptySet(),
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

            SetTable(
                row = row,
                onEvent = onEvent,
                togglingSetIds = togglingSetIds,
                enabled = enabled,
            )

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

            if (entry.isStarted) {
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
            // The one thing the PREV column cannot say for itself. Its cells hold a dash for a
            // set with no history, which is the right answer for the fourth set of an exercise
            // that was three sets last time and the wrong one for an exercise nobody has ever
            // done — a whole column of dashes reads as a fault rather than as a fact. Said
            // once, here, rather than repeated down the column.
            //
            // Under the name rather than beside the eyebrow, because the line above it is not
            // this column's to spend: the superset marker and the two slot controls sit to the
            // right of it, and on a 360dp screen what is left over is not a badge's width.
            //
            // Neutral rather than accent: a first attempt is worth marking and is not worth
            // looking at before the weight being typed two rows below it. See `BadgeTone`.
            if (!row.hasHistory) {
                OpenGymBadge(
                    text = stringResource(R.string.active_first_time),
                    tone = BadgeTone.NEUTRAL,
                    modifier = Modifier.padding(top = OpenGymTheme.spacing.sm),
                )
            }
        }
        if (row.supersetLabel != null) {
            SupersetBadge(label = row.supersetLabel)
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
 *
 * Every row of both halves takes typing. What tells them apart is not whether they can be edited
 * — they all can — but what writes them: a logged row is written by being left, and this one by
 * its tick, because it is not a set until somebody says it is.
 */
@Composable
private fun SetTable(
    row: SessionExerciseRow,
    onEvent: (ActiveSessionEvent) -> Unit,
    togglingSetIds: Set<Long>,
    enabled: Boolean,
) {
    val entry = row.entry
    val error = entry.error?.message()

    Column(modifier = Modifier.fillMaxWidth()) {
        LoggedSetTable(
            sets = row.sets,
            sessionExerciseId = row.sessionExerciseId,
            onEvent = onEvent,
            togglingSetIds = togglingSetIds,
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
            onSetTypeChange = {
                onEvent(ActiveSessionEvent.SetTypeChanged(row.sessionExerciseId, it))
            },
            rpe = entry.rpe,
            onRpeChange = { onEvent(ActiveSessionEvent.RpeChanged(row.sessionExerciseId, it)) },
            // What the next set was last time, which is the one row where that is a
            // prediction rather than a comparison — and the reason the column is worth its
            // width: it is the number a lifter is deciding against with a bar in their hands.
            previous = row.entryPrevious,
            // The one accented row on the card, always. Every row above it is live too — a
            // logged set is corrected where it sits, see [LoggedSetTable] — so what the tint is
            // left to mean is the only thing worth marking from arm's length: this is where the
            // next number goes.
            isActive = true,
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
 * Internal because the same three refusals reach the same user under any row of the table:
 * `SetValidator` gives one answer about a set, whether it is being logged or being corrected, so
 * the screen says it in one set of words.
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
