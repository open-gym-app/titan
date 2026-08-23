package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.CollapsedReorderRow
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.active.LoggedSet
import com.opengym.feature.session.active.SessionExerciseRow
import com.opengym.feature.session.active.SetEntry
import com.opengym.core.ui.R as CoreR

/**
 * One exercise while the workout is being reordered: its place, its name, and what is on it.
 *
 * The row itself is [CollapsedReorderRow], which the template editor's plan collapses to as well
 * — this is the workout's own reading of it, and all it adds is what a `SessionExerciseRow` puts
 * in the two slots: how many sets have been logged, and which superset it is in.
 *
 * @param position where it sits in the order **as currently dragged**, from 0. Not the stored
 *   position: while a drag is in flight the two differ, and the number a lifter is looking at has
 *   to be the one they are creating.
 * @param isHeld whether this is the exercise under the finger. What that looks like belongs to
 *   [CollapsedReorderRow], which draws it the same way for a workout and for a template's plan.
 */
@Composable
internal fun CollapsedExerciseRow(
    row: SessionExerciseRow,
    position: Int,
    modifier: Modifier = Modifier,
    isHeld: Boolean = false,
) {
    CollapsedReorderRow(
        position = position,
        name = row.exercise?.name ?: stringResource(R.string.active_exercise_removed),
        modifier = modifier,
        isHeld = isHeld,
        // Only where there is something to say. "0 sets" against every exercise nobody has
        // reached yet is a column of noise down a list whose subject is the order.
        meta = if (row.setCount > 0) {
            pluralStringResource(CoreR.plurals.session_set_count, row.setCount, row.setCount)
        } else {
            null
        },
        supersetLabel = row.supersetLabel,
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Reordering a workout", showBackground = true, widthDp = 360)
@Composable
fun CollapsedExerciseRowPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        CollapsedWorkoutCatalogue()
    }
}

@Preview(name = "Reordering a workout, dark", showBackground = true, widthDp = 360)
@Composable
fun CollapsedExerciseRowDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        CollapsedWorkoutCatalogue()
    }
}

/** A push day with its third exercise picked up, which is what the whole list looks like. */
@Composable
private fun CollapsedWorkoutCatalogue() {
    Column(
        modifier = Modifier.padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        previewRows().forEachIndexed { index, row ->
            CollapsedExerciseRow(row = row, position = index, isHeld = index == 2)
        }
    }
}

private fun previewRows(): List<SessionExerciseRow> = listOf(
    SessionExerciseRow(
        sessionExerciseId = 1L,
        position = 0,
        exercise = Exercise(id = 1L, name = "Bench press"),
        sets = previewSets(sessionExerciseId = 1L, count = 3),
    ),
    SessionExerciseRow(
        sessionExerciseId = 2L,
        position = 1,
        exercise = Exercise(id = 2L, name = "Incline dumbbell press"),
        sets = previewSets(sessionExerciseId = 2L, count = 2),
    ),
    SessionExerciseRow(
        sessionExerciseId = 3L,
        position = 2,
        exercise = Exercise(id = 3L, name = "Cable fly"),
        supersetLabel = "A1",
        isLinkedToNext = true,
    ),
    SessionExerciseRow(
        sessionExerciseId = 4L,
        position = 3,
        exercise = Exercise(id = 4L, name = "Tricep pushdown"),
        supersetLabel = "A2",
    ),
)

private fun previewSets(sessionExerciseId: Long, count: Int): List<LoggedSet> =
    (1..count).map { number ->
        LoggedSet(
            id = sessionExerciseId * 100 + number,
            entry = SetEntry(weight = "60", reps = "8"),
            isCompleted = true,
        )
    }
