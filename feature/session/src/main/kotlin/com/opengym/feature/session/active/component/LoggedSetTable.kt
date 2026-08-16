package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.LoggedSetRow
import com.opengym.core.ui.component.SetLogHeader

/**
 * The sets logged against one exercise, as the shared four-column table.
 *
 * Drawn the same for the exercise being worked and for every other exercise in the workout,
 * which is the point of it living in one place: two tables stacked on the same screen line their
 * columns up, and a set reads identically wherever it is.
 *
 * The rows are read-only. Reopening one to change it is #99, and until then a tick that could not
 * be un-ticked would be an affordance with nothing behind it.
 *
 * No trailing rule under the last row. The exercise being worked puts its entry row below this
 * and draws the divider that separates them; a table that ended in a line of its own would give
 * that row two, and give every other exercise's table a line under nothing.
 */
@Composable
fun LoggedSetTable(sets: List<WorkoutSet>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SetLogHeader()

        sets.forEachIndexed { index, set ->
            HairlineDivider()
            // #101 fills `previous`. The column is drawn either way, so adding it later does not
            // move every figure in the table sideways.
            LoggedSetRow(setNumber = index + 1, set = set, previous = null)
        }
    }
}
