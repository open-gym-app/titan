package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.R
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.LoggedSetRow
import com.opengym.core.ui.component.SetEditor
import com.opengym.core.ui.component.SetLogHeader
import com.opengym.core.ui.component.SwipeToDelete
import com.opengym.feature.session.active.ActiveSessionEvent
import com.opengym.feature.session.active.SetEdit

/**
 * The sets logged against one exercise, as the shared four-column table.
 *
 * Drawn the same for the exercise being worked and for every other exercise in the workout,
 * which is the point of it living in one place: two tables stacked on the same screen line their
 * columns up, and a set reads identically wherever it is.
 *
 * ### Three things can be done to a row, and each is a different size of gesture
 *
 * **Tapping** it opens it for correction, in place, as [SetEditor] — the same grid with the values
 * in fields. The cells are the affordance and there is no pencil on them: a control on every line
 * of a table whose job is to be read at a glance is chrome that costs more than the action it
 * offers, and a tap on a logged set's values means one thing.
 *
 * **The tick** takes the set back out of the count without taking it out of the workout. It is the
 * one control drawn on every row, because it is the one mistake every row can carry: the tick is
 * how a set is written, so a set written by accident is a tick pressed by accident, and the way
 * back should be the same control rather than four taps inside an editor.
 *
 * **Dragging it off to the left** deletes it. A destructive action needs to be the *hardest*
 * gesture on the row rather than the one nearest the thumb — which is what it was, as a DELETE
 * under the open editor's fields — and it is still reversible: the undo goes up in the bar for as
 * long as the offer stands. See `SwipeToDelete` and `DeleteSetUseCase`.
 *
 * At most one row is open for correction at a time, which is the ViewModel's rule rather than this
 * table's — see [SetEdit]. What arrives here is the open editor if it belongs to *this* exercise
 * and null otherwise, so nothing here has to ask. The row being corrected is not draggable while
 * it is open: it is a row of fields, and a drag across a field is how a cursor gets moved.
 *
 * ### No trailing rule under the last row
 *
 * The exercise being worked puts its entry row below this and draws the divider that separates
 * them; a table that ended in a line of its own would give that row two, and give every other
 * exercise's table a line under nothing.
 *
 * @param sessionExerciseId what a tap, a tick or a drag on a row names, since a set number alone
 *   does not identify a set on a screen holding the whole workout.
 * @param editing the set of this exercise open for correction, or null.
 * @param canSaveEdit whether the open editor's tick is live — see
 *   [com.opengym.feature.session.active.SessionExerciseRow.canSaveEdit]. Ignored when nothing is
 *   open.
 * @param togglingSetIds the sets whose tick is being written. Those rows' ticks go inert, so a
 *   second tap cannot send the same set both ways at once.
 * @param enabled false leaves the table readable and untouchable: a paused workout takes no
 *   writes, so a row that still opened would open an editor whose every button is refused, and a
 *   drag that still deleted would be a deletion the domain throws out.
 */
@Composable
fun LoggedSetTable(
    sets: List<WorkoutSet>,
    sessionExerciseId: Long,
    onEvent: (ActiveSessionEvent) -> Unit,
    modifier: Modifier = Modifier,
    editing: SetEdit? = null,
    canSaveEdit: Boolean = false,
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

            if (editing?.setId == set.id) {
                SetEditor(
                    setNumber = setNumber,
                    weight = editing.entry.weight,
                    reps = editing.entry.reps,
                    onWeightChange = { onEvent(ActiveSessionEvent.SetEditWeightChanged(it)) },
                    onRepsChange = { onEvent(ActiveSessionEvent.SetEditRepsChanged(it)) },
                    rpe = editing.entry.rpe,
                    onRpeChange = { onEvent(ActiveSessionEvent.SetEditRpeChanged(it)) },
                    setType = editing.entry.setType,
                    onSetTypeChange = { onEvent(ActiveSessionEvent.SetEditTypeChanged(it)) },
                    onSave = { onEvent(ActiveSessionEvent.SetEditConfirmed) },
                    onCancel = { onEvent(ActiveSessionEvent.SetEditCancelled) },
                    // #101 fills this, here and on the rows around it.
                    previous = null,
                    error = editing.entry.error?.message(),
                    // Inert while its own write is in flight, for the reason the rename dialog
                    // is: the fields are the only copy of what was typed, and a second tick
                    // would write the same correction twice.
                    enabled = enabled && !editing.isSaving,
                    canSave = canSaveEdit,
                )
            } else {
                // Keyed, because the gesture's position is remembered by slot: without this, a set
                // deleted from the middle of the table would hand its half-finished drag to the
                // row that moves up into its place.
                key(set.id) {
                    SwipeToDelete(
                        onDelete = {
                            onEvent(ActiveSessionEvent.SetDeleted(sessionExerciseId, set.id))
                        },
                        actionLabel = stringResource(R.string.set_delete_action, setNumber),
                        enabled = enabled,
                    ) {
                        LoggedSetRow(
                            setNumber = setNumber,
                            set = set,
                            previous = null,
                            onClick = {
                                onEvent(
                                    ActiveSessionEvent.SetEditRequested(sessionExerciseId, set.id),
                                )
                            }.takeIf { enabled },
                            onCompletedChange = { isCompleted: Boolean ->
                                onEvent(
                                    ActiveSessionEvent.SetCompletionToggled(
                                        sessionExerciseId = sessionExerciseId,
                                        setId = set.id,
                                        isCompleted = isCompleted,
                                    ),
                                )
                            }.takeIf { enabled && set.id !in togglingSetIds },
                        )
                    }
                }
            }
        }
    }
}
