package com.opengym.feature.session.active.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.DragHandle
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.active.SessionExerciseRow
import com.opengym.core.ui.R as CoreR

/**
 * One exercise while the workout is being reordered: its place, its name, and what is on it.
 *
 * Every card on the screen shrinks to one of these the moment an exercise is picked up, and that
 * is the point of them rather than a side effect. A workout is eight cards and a thousand pixels
 * of set tables; dragging the sixth exercise above the second in that layout means dragging into
 * a screen edge and waiting for it to scroll. Collapsed, the whole session is one screen, and the
 * move is one movement of the thumb.
 *
 * What is left is what the decision needs: where the exercise is in the order, what it is, and
 * whether it has been worked yet. The sets themselves, the entry row and every control on the
 * card are gone for the duration — nothing on them can be used while a finger is holding an
 * exercise, and each one drawn is a row somebody has to drag past.
 *
 * @param position where it sits in the order **as currently dragged**, from 0. Not the stored
 *   position: while a drag is in flight the two differ, and the number a lifter is looking at has
 *   to be the one they are creating.
 * @param isHeld whether this is the exercise under the finger. Drawn as the one accented card on
 *   the screen, which is what "picked up" looks like here — the accent is spent on the thing that
 *   matters right now, and while a drag is in flight nothing else does.
 */
@Composable
internal fun CollapsedExerciseRow(
    row: SessionExerciseRow,
    position: Int,
    modifier: Modifier = Modifier,
    isHeld: Boolean = false,
) {
    val name = row.exercise?.name ?: stringResource(R.string.active_exercise_removed)
    // Resolved before the semantics block, which is not a composable context.
    val description = stringResource(R.string.active_reorder_position, position + 1, name)

    OpenGymCard(
        modifier = modifier
            .fillMaxWidth()
            // One node, read as one line: "3. Cable fly". The parts of the row — a handle, a
            // number, a name, a count — are four stops in the traversal order and none of them
            // means anything on its own, and the moves themselves are offered as actions on the
            // item this sits in. See `ReorderableItem`.
            .clearAndSetSemantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        accented = isHeld,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OpenGymTheme.spacing.lg,
                    vertical = OpenGymTheme.spacing.md,
                ),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DragHandle(
                modifier = Modifier.size(HANDLE_SIZE),
                tint = if (isHeld) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
            Text(
                text = (position + 1).toString(),
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Only where there is something to say. "0 sets" against every exercise nobody has
            // reached yet is a column of noise down a list whose subject is the order.
            if (row.setCount > 0) {
                Text(
                    text = pluralStringResource(
                        CoreR.plurals.session_set_count,
                        row.setCount,
                        row.setCount,
                    ),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.supersetLabel != null) {
                OpenGymBadge(
                    text = stringResource(R.string.active_superset, row.supersetLabel),
                    tone = BadgeTone.ACCENT,
                )
            }
        }
    }
}

/** Matches the glyph size the set rows use, so the column of handles lines up with the numbers. */
private val HANDLE_SIZE: Dp = 18.dp

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

private fun previewSets(sessionExerciseId: Long, count: Int): List<WorkoutSet> =
    (1..count).map { number ->
        WorkoutSet(
            id = sessionExerciseId * 100 + number,
            sessionExerciseId = sessionExerciseId,
            setNumber = number,
            reps = 8,
            weight = 60_000_000L,
            isCompleted = true,
            completedAt = 1L,
            createdAt = 1L,
            updatedAt = 1L,
        )
    }
