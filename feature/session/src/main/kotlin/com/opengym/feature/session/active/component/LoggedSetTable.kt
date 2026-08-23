package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.R
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.SetLogHeader
import com.opengym.core.ui.component.SetLogRow
import com.opengym.core.ui.component.SwipeToDelete
import com.opengym.feature.session.active.ActiveSessionEvent
import com.opengym.feature.session.active.LoggedSet

/**
 * The sets logged against one exercise, as the shared four-column table.
 *
 * Drawn the same for the exercise being worked and for every other exercise in the workout,
 * which is the point of it living in one place: two tables stacked on the same screen line their
 * columns up, and a set reads identically wherever it is.
 *
 * ### Every row is live, and there is no editor
 *
 * A logged set is the same [SetLogRow] the next set is typed into, with the values already in the
 * fields. Not a mode, not a panel, and nothing to open: the row a lifter wants to correct is
 * already the row they can correct, and the sets above and below it — which is what a mistyped
 * weight is *noticed* against, 60, 70, **85**, 80 — never move.
 *
 * This replaced a `SetEditor`, which was the same grid with a tick and a CANCEL under it, opened
 * by tapping a row. Two things were wrong with it. It drew a panel to say what the row already
 * said — the fields were in the same cells at the same widths, so the whole ceremony bought a
 * footer — and it made a lifter tap a set before they could touch it, on a screen whose every
 * other row takes typing straight away. What is left is the row.
 *
 * ### So what writes it
 *
 * Leaving it. Focus goes from the row — a tap on another one, on the entry row, anywhere off the
 * fields — or the keyboard's DONE is pressed, and what is in the fields is written; see
 * `ActiveSessionEvent.SetEditCommitted`. A row nobody typed into writes nothing, and neither does
 * one typed back to what it said. The rating and the set type are the exception and are written
 * the moment they are picked, because a menu's answer is not something you leave a row to finish.
 *
 * A row can also be left by *scrolling*, which is the case focus does not cover: the workout is a
 * lazy list, so a card pushed far enough off the screen is taken out of the composition with a
 * half-typed correction still in it, and the row is gone before it can report that it lost focus.
 * So leaving the composition counts as leaving the row. It costs nothing when there is nothing to
 * write — the commonest case by a distance — and it is what makes "type, then scroll away" write
 * the set rather than quietly hold it until the row is next touched.
 *
 * There is deliberately no confirm and no cancel. A confirm over a table where every row is
 * always editable is a button that is live on all of them at once and means something on one; and
 * the way back from a correction is to type the old number in, which is the same gesture as the
 * correction and needs no vocabulary of its own.
 *
 * ### The two other things a row can carry
 *
 * **The tick** takes the set back out of the count without taking it out of the workout. The one
 * mistake every row can carry: the tick is how a set is written, so a set written by accident is
 * a tick pressed by accident, and the way back is the same control.
 *
 * **Dragging it off to the left** deletes it. A destructive action needs to be the *hardest*
 * gesture on the row rather than the one nearest the thumb, and it is still reversible: the undo
 * goes up in the bar for as long as the offer stands. It is a drag from the row's furniture — the
 * set number, the rating, the margins — because the two value cells are fields now and a drag
 * across a field is how a cursor gets moved. See `SwipeToDelete` and `DeleteSetUseCase`.
 *
 * ### No trailing rule under the last row
 *
 * The exercise being worked puts its entry row below this and draws the divider that separates
 * them; a table that ended in a line of its own would give that row two, and give every other
 * exercise's table a line under nothing.
 *
 * @param sessionExerciseId what a keystroke, a tick or a drag on a row names, since a set number
 *   alone does not identify a set on a screen holding the whole workout.
 * @param togglingSetIds the sets whose tick is being written. Those rows' ticks go inert, so a
 *   second tap cannot send the same set both ways at once.
 * @param enabled false leaves the table readable and untouchable: a paused workout takes no
 *   writes, so fields that still accepted typing would be collecting keystrokes for a write that
 *   is refused, and a drag that still deleted would be a deletion the domain throws out. The
 *   values stay on screen, because they are still the user's copy of them.
 */
@Composable
fun LoggedSetTable(
    sets: List<LoggedSet>,
    sessionExerciseId: Long,
    onEvent: (ActiveSessionEvent) -> Unit,
    modifier: Modifier = Modifier,
    togglingSetIds: Set<Long> = emptySet(),
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SetLogHeader()

        sets.forEachIndexed { index, set ->
            HairlineDivider()

            // The row's place in the table, not the stored number, and the same one for every row
            // here: the two agree, because a deletion renumbers — and where they briefly do not,
            // what the user is looking at is the position.
            val setNumber = index + 1

            // Keyed, because both the gesture's position and the row's focus history are
            // remembered by slot: without this, a set deleted from the middle of the table would
            // hand its half-finished drag — and its "these fields were focused" — to the row that
            // moves up into its place, which would commit one row's correction under another
            // row's id.
            key(set.id) {
                // Scrolled out of the list, collapsed for a reorder, rotated, navigated away
                // from: the row has been left, and there is no focus event for it. Only when it
                // holds typing — a table of untouched rows leaves the composition several times
                // in an ordinary workout, and none of those is a correction. Both values are read
                // through [rememberUpdatedState] because this outlives every recomposition of the
                // row and must not answer with yesterday's.
                val isEdited by rememberUpdatedState(set.isEdited)
                val onCurrentEvent by rememberUpdatedState(onEvent)
                DisposableEffect(set.id) {
                    onDispose {
                        if (isEdited) {
                            onCurrentEvent(
                                ActiveSessionEvent.SetEditCommitted(sessionExerciseId, set.id),
                            )
                        }
                    }
                }

                SwipeToDelete(
                    onDelete = { onEvent(ActiveSessionEvent.SetDeleted(sessionExerciseId, set.id)) },
                    actionLabel = stringResource(R.string.set_delete_action, setNumber),
                    enabled = enabled,
                ) {
                    LoggedSetRow(
                        setNumber = setNumber,
                        set = set,
                        sessionExerciseId = sessionExerciseId,
                        onEvent = onEvent,
                        // Inert while its own correction is in flight, for the reason the rename
                        // dialog is: the fields are the only copy of what was typed, and a second
                        // commit would write the same correction twice.
                        enabled = enabled && !set.isSaving,
                        canComplete = enabled && set.id !in togglingSetIds && set.canComplete,
                    )
                }
            }
        }
    }
}

/**
 * One logged set, in the row it is corrected in.
 *
 * Split out only to keep the table's loop readable: nine callbacks against one id is a paragraph,
 * and it is the same paragraph for every row.
 */
@Composable
private fun LoggedSetRow(
    setNumber: Int,
    set: LoggedSet,
    sessionExerciseId: Long,
    onEvent: (ActiveSessionEvent) -> Unit,
    enabled: Boolean,
    canComplete: Boolean,
) {
    SetLogRow(
        setNumber = setNumber,
        weight = set.entry.weight,
        reps = set.entry.reps,
        onWeightChange = {
            onEvent(ActiveSessionEvent.SetEditWeightChanged(sessionExerciseId, set.id, it))
        },
        onRepsChange = {
            onEvent(ActiveSessionEvent.SetEditRepsChanged(sessionExerciseId, set.id, it))
        },
        onCompletedChange = {
            onEvent(
                ActiveSessionEvent.SetCompletionToggled(
                    sessionExerciseId = sessionExerciseId,
                    setId = set.id,
                    isCompleted = it,
                ),
            )
        },
        setType = set.entry.setType,
        onSetTypeChange = {
            onEvent(ActiveSessionEvent.SetEditTypeChanged(sessionExerciseId, set.id, it))
        },
        rpe = set.entry.rpe,
        onRpeChange = {
            onEvent(ActiveSessionEvent.SetEditRpeChanged(sessionExerciseId, set.id, it))
        },
        // What this set number did last time, aligned by the ViewModel rather than by this
        // table: which reference a row gets depends on the *types* of the rows above it, which
        // is a fact about the exercise and not about the row. See [SessionExerciseRow].
        previous = set.previous,
        isCompleted = set.isCompleted,
        // The accent stays on the entry row. A logged set being typed into is not "where the next
        // number goes", which is the one thing the tint is for, and a table that lit up under
        // every thumb would have no "here" left.
        isActive = false,
        error = set.entry.error?.message(),
        enabled = enabled,
        canComplete = canComplete,
        onCommit = { onEvent(ActiveSessionEvent.SetEditCommitted(sessionExerciseId, set.id)) },
    )
}
