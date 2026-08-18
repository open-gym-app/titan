package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.LoggedSetRow
import com.opengym.core.ui.component.SetEditor
import com.opengym.core.ui.component.SetLogHeader
import com.opengym.feature.session.active.ActiveSessionEvent
import com.opengym.feature.session.active.SetEdit

/**
 * The sets logged against one exercise, as the shared four-column table.
 *
 * Drawn the same for the exercise being worked and for every other exercise in the workout,
 * which is the point of it living in one place: two tables stacked on the same screen line their
 * columns up, and a set reads identically wherever it is.
 *
 * ### Any row can be reopened
 *
 * A tap on a logged set turns it into [SetEditor] in place — the same grid, with the values in
 * fields and the ways out under them. The row is the affordance and there is no pencil on it: a
 * control on every line of a table whose job is to be read at a glance is chrome that costs more
 * than the action it offers, and a tap on a logged set means one thing.
 *
 * At most one is open at a time, which is the ViewModel's rule rather than this table's — see
 * [SetEdit]. What arrives here is the open editor if it belongs to *this* exercise and null
 * otherwise, so nothing here has to ask.
 *
 * ### No trailing rule under the last row
 *
 * The exercise being worked puts its entry row below this and draws the divider that separates
 * them; a table that ended in a line of its own would give that row two, and give every other
 * exercise's table a line under nothing.
 *
 * @param sessionExerciseId what a tap on a row names, since a set number alone does not identify
 *   a set on a screen holding the whole workout.
 * @param editing the set of this exercise open for correction, or null.
 * @param canSaveEdit whether the open editor's tick is live — see
 *   [com.opengym.feature.session.active.SessionExerciseRow.canSaveEdit]. Ignored when nothing is
 *   open.
 * @param enabled false leaves the table readable and untouchable: a paused workout takes no
 *   writes, so a row that still opened would open an editor whose every button is refused.
 */
@Composable
fun LoggedSetTable(
    sets: List<WorkoutSet>,
    sessionExerciseId: Long,
    onEvent: (ActiveSessionEvent) -> Unit,
    modifier: Modifier = Modifier,
    editing: SetEdit? = null,
    canSaveEdit: Boolean = false,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SetLogHeader()

        sets.forEachIndexed { index, set ->
            HairlineDivider()

            if (editing?.setId == set.id) {
                SetEditor(
                    // The row's place in the table, not the stored number, for the reason the
                    // read-only rows below use the same: the two agree, because a deletion
                    // renumbers — and where they briefly do not, what the user is looking at is
                    // the position.
                    setNumber = index + 1,
                    weight = editing.entry.weight,
                    reps = editing.entry.reps,
                    onWeightChange = { onEvent(ActiveSessionEvent.SetEditWeightChanged(it)) },
                    onRepsChange = { onEvent(ActiveSessionEvent.SetEditRepsChanged(it)) },
                    rpe = editing.entry.rpe,
                    onRpeChange = { onEvent(ActiveSessionEvent.SetEditRpeChanged(it)) },
                    setType = editing.entry.setType,
                    onSetTypeChange = { onEvent(ActiveSessionEvent.SetEditTypeChanged(it)) },
                    onSave = { onEvent(ActiveSessionEvent.SetEditConfirmed) },
                    onDelete = { onEvent(ActiveSessionEvent.SetDeleted) },
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
                LoggedSetRow(
                    setNumber = index + 1,
                    set = set,
                    previous = null,
                    onClick = {
                        onEvent(ActiveSessionEvent.SetEditRequested(sessionExerciseId, set.id))
                    }.takeIf { enabled },
                )
            }
        }
    }
}
