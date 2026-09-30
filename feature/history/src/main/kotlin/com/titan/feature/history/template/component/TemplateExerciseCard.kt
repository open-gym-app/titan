package com.titan.feature.history.template.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.UnitSystem
import com.titan.core.ui.component.DragHandle
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PlannedSetHeader
import com.titan.core.ui.component.PlannedSetRow
import com.titan.core.ui.component.SupersetBadge
import com.titan.core.ui.component.SwipeToDelete
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.history.R
import com.titan.feature.history.template.TemplateExerciseRow
import com.titan.feature.history.template.TemplateSetField

/**
 * One exercise of a template: what it is, and every set of it that is planned.
 *
 * ```text
 * ┌──────────────────────────────────────────┐
 * │ ⣿ EXERCISE 1                  SS A1  ⇄  × │
 * │ Bench press                              │
 * │    SET   KG   ×  REPS                    │
 * │    W    [40]  ×  [12]                    │
 * │    2    [80]  ×  [8]                     │
 * │    D    [60]  ×  [10]                    │
 * │    + Add set                             │
 * └──────────────────────────────────────────┘
 * ```
 *
 * ### A plan is a list of sets, not four numbers
 *
 * This card used to hold `SETS · REPS · KG · REST` — four cells describing *n identical sets*.
 * Almost nobody trains that way: the first set is a warm-up, the working sets are not always the
 * same weight, and what ends an exercise is often a drop set. A template that could only say
 * "4 × 8 × 80" could not hold any of that, so the plan a user wrote down was never the workout
 * they did.
 *
 * What is drawn now is the table the workout screen draws, one row per set, in the order they
 * are performed — see [PlannedSetRow]. Ticks and ratings are not on it, because a plan has not
 * happened yet; everything else lines up cell for cell with the session it becomes, which is the
 * point: a lifter should recognise their template when it opens as a workout.
 *
 * There is no rest field. Rest is not a property of a plan on this screen: the workout's timer
 * runs on the user's configured default (`UserPreferences.defaultRestDuration`) whatever the
 * template says, so a per-exercise picker here set a number nothing would ever read.
 *
 * ### The heading is the workout's heading
 *
 * Name *under* its position rather than beside it, in `titleMedium`, exactly as the card this
 * card is a plan for draws it — see `SessionExerciseCard.ExerciseHeading`. The name was on the
 * position's own line in `titleSmall`, which made a template read as a smaller, denser thing than
 * the workout it becomes, and squeezed the name into whatever the controls left of one line: a
 * two-word movement wrapped mid-name beside them. Given the line to itself it takes the width of
 * the card.
 *
 * ### The slot's two controls, and the grab glyph in front of them
 *
 * ⇄ before ×, because it is the milder of the two: swapping keeps the slot and the × takes it
 * away, and a thumb reaching past the destructive control to find the reversible one is the
 * wrong way round. Both sit on the header row, where the workout's own card puts them.
 *
 * The grab glyph is a *mark*, not a control — nothing happens if it is pressed, the whole card
 * responds to a hold — and it is the same glyph the row collapses to when one is picked up, so
 * the two teach each other. See `CollapsedReorderRow`.
 *
 * ### The two destructive gestures are deliberately different
 *
 * A **set** is swiped away, matching the workout's own table: a row's fields are the width of the
 * row, so a horizontal drag is the only gesture left that is not typing, and it should be the
 * hardest one on it. The **exercise** is closed with the × in its header, because the card is
 * also the thing being dragged to reorder the plan — a swipe on an item that is picked up by
 * holding it is two gestures competing for one finger.
 *
 * @param position where it sits in the plan, from 0. The *drawn* order, which while a drag is in
 *   flight is not the stored one.
 * @param onSwap plan a different movement in this slot, keeping its place, its superset and the
 *   sets already written into it. The picker is what answers; see
 *   `TemplateEditorViewModel.swapExercise` for why the sets stay where the workout screen's own
 *   swap refuses to carry them.
 * @param isMovable whether holding this card would pick it up, which is the only thing that draws
 *   the grab glyph in front of its position. The gesture has nothing else on the page announcing
 *   it, and the one thing worse than an unannounced gesture is one announced where it does not
 *   work — a plan of one exercise has nothing to reorder.
 * @param unitSystem what the weight column is captioned and spoken in. Passed rather than read
 *   off `LocalUnitSystem`, because it has to be the unit the draft was *formatted in and will be
 *   parsed back with* — see `TemplateEditorUiState.unitSystem`.
 * @param enabled false makes the whole card read-only — a write is out. The fields keep what
 *   they hold, because that is still the user's copy of it.
 */
@Composable
internal fun TemplateExerciseCard(
    row: TemplateExerciseRow,
    position: Int,
    onSetFieldChange: (setKey: Long, TemplateSetField, String) -> Unit,
    onSetTypeChange: (setKey: Long, SetType) -> Unit,
    onSetAdd: () -> Unit,
    onSetRemove: (setKey: Long) -> Unit,
    onSwap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    enabled: Boolean = true,
    isMovable: Boolean = false,
) {
    val name = row.exercise?.name ?: stringResource(R.string.template_exercise_removed)

    TitanCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(vertical = TitanTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
        ) {
            Header(
                name = name,
                position = position,
                supersetLabel = row.supersetLabel,
                onSwap = onSwap,
                onRemove = onRemove,
                enabled = enabled,
                isMovable = isMovable,
            )

            PlanTable(
                row = row,
                name = name,
                unitSystem = unitSystem,
                enabled = enabled,
                onSetFieldChange = onSetFieldChange,
                onSetTypeChange = onSetTypeChange,
                onSetRemove = onSetRemove,
            )

            AddSetButton(
                canAddSet = row.canAddSet,
                enabled = enabled,
                onClick = onSetAdd,
            )
        }
    }
}

/** The exercise's own heading: where it sits, what it is, and the two ways to change it. */
@Composable
private fun Header(
    name: String,
    position: Int,
    supersetLabel: String?,
    onSwap: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean,
    isMovable: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanTheme.spacing.card),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        // Top rather than centre: the name may take two lines, and controls that centred against
        // it would sit lower on a long name than on a short one.
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Drawn only where the hold would actually do something, and quietly: it is on
                // every card of the plan and none of them is about it.
                if (isMovable) {
                    DragHandle(
                        modifier = Modifier.size(HANDLE_SIZE),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Eyebrow(text = stringResource(R.string.template_exercise_position, position + 1))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TitanTheme.spacing.xs),
            )
        }
        if (supersetLabel != null) SupersetBadge(label = supersetLabel)
        // Before the ×, for the reason on the class: the reversible control should not be the one
        // a thumb reaches past the destructive one to find.
        IconButton(onClick = onSwap, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.SwapHoriz,
                contentDescription = stringResource(R.string.template_swap_exercise, name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.template_remove_exercise, name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The sets, in the order they are performed.
 *
 * Keyed on the set's own key rather than on its index, for the reason the workout's table is: a
 * set removed from the middle would otherwise hand its half-finished swipe to the row that moves
 * up into its place.
 *
 * An exercise with nothing planned draws no table at all — not an empty header over a rule. It is
 * a legitimate line ("do this, sets to be decided") and ADD SET is directly under it.
 */
@Composable
private fun PlanTable(
    row: TemplateExerciseRow,
    name: String,
    unitSystem: UnitSystem,
    enabled: Boolean,
    onSetFieldChange: (setKey: Long, TemplateSetField, String) -> Unit,
    onSetTypeChange: (setKey: Long, SetType) -> Unit,
    onSetRemove: (setKey: Long) -> Unit,
) {
    if (row.sets.isEmpty()) return

    val invalid = stringResource(R.string.template_field_invalid)

    Column(modifier = Modifier.fillMaxWidth()) {
        PlannedSetHeader(weightUnit = unitSystem.weightSuffix)

        row.sets.forEachIndexed { index, set ->
            HairlineDivider()

            key(set.key) {
                SwipeToDelete(
                    onDelete = { onSetRemove(set.key) },
                    actionLabel = stringResource(R.string.template_remove_set, index + 1, name),
                    enabled = enabled,
                ) {
                    PlannedSetRow(
                        setNumber = index + 1,
                        weight = set.weight,
                        reps = set.reps,
                        onWeightChange = { onSetFieldChange(set.key, TemplateSetField.WEIGHT, it) },
                        onRepsChange = { onSetFieldChange(set.key, TemplateSetField.REPS, it) },
                        setType = set.setType,
                        onSetTypeChange = { onSetTypeChange(set.key, it) },
                        error = invalid.takeIf { set.errors.isNotEmpty() },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

/**
 * ADD SET, on its own line under the table.
 *
 * Full width rather than sized to its label: it is the only control the card's foot holds, and a
 * short button floating at one end of an empty line reads as something that lost its neighbour.
 */
@Composable
private fun AddSetButton(
    canAddSet: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        // Inert at the ceiling rather than absent: a control that vanishes when a plan gets
        // long reads as a bug, where a greyed one reads as a limit.
        enabled = enabled && canAddSet,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanTheme.spacing.card),
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(ADD_ICON_SIZE),
        )
        Text(
            text = stringResource(R.string.template_add_set),
            modifier = Modifier.padding(start = TitanTheme.spacing.xs),
        )
    }
}

/** The size of the one control that is not one of the workout card's own. */
private val ADD_ICON_SIZE: Dp = 18.dp

/** Smaller than the controls it sits opposite, because it is a mark and not one of them. */
private val HANDLE_SIZE: Dp = 14.dp
