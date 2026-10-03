package com.titan.core.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.titan.core.domain.model.ExerciseType
import com.titan.core.ui.R
import com.titan.core.ui.format.DistanceUnit

/*
 * The two value cells of a set row, by logging mode (#M7-17).
 *
 * Every set table in the app — the row being typed, a logged row being corrected, a template's
 * planned row — draws two value fields between its fixed cells, and which two they are is the
 * exercise's logging mode:
 *
 * | Mode        | First field         | Second field |
 * | ----------- | ------------------- | ------------ |
 * | `STRENGTH`  | weight              | reps         |
 * | `ENDURANCE` | distance            | time         |
 * | `ISOMETRIC` | time                | added weight |
 *
 * Two in every mode, in the same two cells, which is what lets the grid in `SetLogRow` stay one
 * grid: the fixed columns, the clearances and `setTableFitsPrevious` hold whichever pair is
 * drawn. The field a mode cannot do without is the one `SetValidator.requiredDimension` names —
 * reps, or a time — and it is never the narrower of the two cells where its text is the longer:
 * a run's time takes the wider share, because `1:05:30` is seven characters and a distance
 * rarely more than four.
 *
 * Kept in one place so a row and the header over it cannot disagree about the order, which would
 * be a table whose labels sit over the wrong fields.
 */

/**
 * What is typed into a row's value fields, as text — for the reason `SetLogRow` gives at length.
 *
 * All four travel together whatever the mode, because a mode only decides which two are *drawn*:
 * a set carries every dimension `WorkoutSet` has, and a row that dropped the ones it does not
 * draw would be a row that could not be handed back unchanged.
 */
@Immutable
internal data class SetFieldValues(
    val weight: String = "",
    val reps: String = "",
    val duration: String = "",
    val distance: String = "",
)

/**
 * The two value fields of a row of [exerciseType], in order — see the table at the top.
 *
 * @param weightPlaceholder what the weight field shows while empty — the next-load target.
 * @param repsPlaceholder the same for the reps field.
 */
@Composable
@Suppress("LongParameterList")
internal fun RowScope.SetValueFields(
    exerciseType: ExerciseType,
    setNumber: Int,
    values: SetFieldValues,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onDistanceChange: (String) -> Unit,
    distanceUnit: DistanceUnit,
    enabled: Boolean,
    error: String?,
    weightPlaceholder: String? = null,
    repsPlaceholder: String? = null,
    onDone: (() -> Unit)? = null,
) {
    // The time field takes a decimal pad rather than the full keyboard: the colon is not on it,
    // so the parser reads a full stop or a comma as one — see `parseClockSeconds`.
    val timeHint = stringResource(R.string.set_log_time_placeholder)

    @Composable
    fun weightField(
        share: Float,
        description: String,
    ) = TitanValueField(
        value = values.weight,
        onValueChange = onWeightChange,
        description = description,
        keyboardType = KeyboardType.Decimal,
        enabled = enabled,
        error = error,
        placeholder = weightPlaceholder,
        onDone = onDone,
        modifier = Modifier.weight(share),
    )

    @Composable
    fun timeField(share: Float) =
        TitanValueField(
            value = values.duration,
            onValueChange = onDurationChange,
            description = stringResource(R.string.set_log_duration_field, setNumber),
            keyboardType = KeyboardType.Decimal,
            enabled = enabled,
            error = error,
            placeholder = timeHint,
            onDone = onDone,
            modifier = Modifier.weight(share),
        )

    when (exerciseType) {
        ExerciseType.STRENGTH -> {
            weightField(WEIGHT_COLUMN_WEIGHT, stringResource(R.string.set_log_weight_field, setNumber))
            TitanValueField(
                value = values.reps,
                onValueChange = onRepsChange,
                description = stringResource(R.string.set_log_reps_field, setNumber),
                keyboardType = KeyboardType.Number,
                enabled = enabled,
                error = error,
                placeholder = repsPlaceholder,
                onDone = onDone,
                modifier = Modifier.weight(REPS_COLUMN_WEIGHT),
            )
        }

        ExerciseType.ENDURANCE -> {
            TitanValueField(
                value = values.distance,
                onValueChange = onDistanceChange,
                // The unit is spoken because it is not the ambient one: a run is typed in
                // kilometres and a swim in metres, and the header saying so is hidden from
                // a screen reader.
                description = stringResource(R.string.set_log_distance_field, setNumber, distanceUnit.suffix),
                keyboardType = KeyboardType.Decimal,
                enabled = enabled,
                error = error,
                onDone = onDone,
                modifier = Modifier.weight(REPS_COLUMN_WEIGHT),
            )
            timeField(WEIGHT_COLUMN_WEIGHT)
        }

        ExerciseType.ISOMETRIC -> {
            timeField(WEIGHT_COLUMN_WEIGHT)
            weightField(REPS_COLUMN_WEIGHT, stringResource(R.string.set_log_added_weight_field, setNumber))
        }
    }
}

/**
 * The header labels over [SetValueFields], in the same order and at the same shares.
 *
 * @param weightUnit the weight column's unit — the ambient one on a workout, the draft's on a
 *   template. See `PlannedSetHeader`.
 */
@Composable
internal fun RowScope.SetValueLabels(
    exerciseType: ExerciseType,
    weightUnit: String,
    distanceUnit: DistanceUnit,
) {
    val time = stringResource(R.string.set_log_column_time)

    @Composable
    fun label(
        text: String,
        share: Float,
    ) = Eyebrow(text = text, textAlign = TextAlign.Center, modifier = Modifier.weight(share))

    when (exerciseType) {
        ExerciseType.STRENGTH -> {
            label(weightUnit, WEIGHT_COLUMN_WEIGHT)
            label(stringResource(R.string.set_log_column_reps), REPS_COLUMN_WEIGHT)
        }

        ExerciseType.ENDURANCE -> {
            label(distanceUnit.suffix, REPS_COLUMN_WEIGHT)
            label(time, WEIGHT_COLUMN_WEIGHT)
        }

        ExerciseType.ISOMETRIC -> {
            label(time, WEIGHT_COLUMN_WEIGHT)
            label(weightUnit, REPS_COLUMN_WEIGHT)
        }
    }
}
