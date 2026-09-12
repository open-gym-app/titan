package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.opengym.core.domain.model.SetType
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * One set of a *plan*: `W | [40] [12]`.
 *
 * The template editor's row, and the third member of [SetLogRow]'s family — the row being
 * typed, the row that is a record, and this one, the row that has not happened yet. It lives
 * beside them and draws their cells, because the whole point of it is that a template looks
 * like the workout it plans: the leading marker is the same [SetTypePicker], the two figures
 * are the same [OpenGymValueField]s in the same order under the same headings, so a lifter
 * reading a template reads it the way they will read the session.
 *
 * ### What it drops, and why
 *
 * **The tick.** A plan cannot be performed — that is what makes it a plan — and a tick on it
 * would be a control whose only meaning is on another screen.
 *
 * **The PREV column.** It answers "what did you do last time", which a template has no session
 * to ask about. Dropping it is also what buys the two fields room enough to be typed into on a
 * card that is already indented inside an exercise.
 *
 * **The rating.** A template does not plan how hard a set will feel — see
 * [com.opengym.core.domain.model.PlannedSet].
 *
 * **The trailing space the tick used to hold goes with it.** It was held open so a plan's fields
 * and the fields of the workout it becomes would sit at the same width — but the two tables are
 * never on screen together, so nothing was ever compared, and what it actually bought was a 32dp
 * gutter down the right of every template card. On a phone that reads as a table shoved to the
 * left of the card holding it. The two figures take that width instead.
 *
 * ### Deleting a set is not here
 *
 * It is a swipe, and it belongs to whatever draws the table — the same `SwipeToDelete` the
 * workout's own set table uses, for the same reason: a destructive action on a row should be
 * the hardest gesture on it, and both figures here are fields, which a horizontal drag would
 * otherwise be moving a cursor in.
 *
 * @param setNumber the set's place in the plan, from 1. Not held by the set itself, which has
 *   no number: a list is already ordered.
 * @param weight the weight field's contents, in the ambient unit — text, not a number, for the
 *   reason [SetLogRow] gives at length.
 * @param reps the rep field's contents.
 * @param error the reason the row cannot be planned, drawn under it. Null when it is fine. A
 *   blank field is never an error here: a plan is allowed to decline to say.
 * @param enabled false leaves the row readable and untouchable — a save is in flight.
 */
@Composable
fun PlannedSetRow(
    setNumber: Int,
    weight: String,
    reps: String,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    setType: SetType = SetType.NORMAL,
    onSetTypeChange: ((SetType) -> Unit)? = null,
    error: String? = null,
    enabled: Boolean = true,
) {
    SetRowFrame(
        modifier = modifier,
        isActive = false,
        // The plan's own ground: every row of it is a set nobody has performed, which is
        // exactly the state the frame draws its faintest fill for.
        isCompleted = false,
        error = error,
    ) {
        SetTypeCell(
            setNumber = setNumber,
            setType = setType,
            onSetTypeChange = onSetTypeChange,
            isActive = false,
            enabled = enabled,
        )

        OpenGymValueField(
            value = weight,
            onValueChange = onWeightChange,
            description = stringResource(R.string.set_log_weight_field, setNumber),
            keyboardType = KeyboardType.Decimal,
            enabled = enabled,
            error = error,
            modifier = Modifier.weight(WEIGHT_COLUMN_WEIGHT),
        )
        OpenGymValueField(
            value = reps,
            onValueChange = onRepsChange,
            description = stringResource(R.string.set_log_reps_field, setNumber),
            keyboardType = KeyboardType.Number,
            enabled = enabled,
            error = error,
            modifier = Modifier.weight(REPS_COLUMN_WEIGHT),
        )
    }
}

/**
 * The plan's column labels: `SET`, `KG`, `REPS`.
 *
 * [SetLogHeader] without the columns [PlannedSetRow] drops. Separate rather than a flag on
 * that one, because it is not the same table: this header sits over rows whose widths are this
 * file's, and a header that took its columns from a parameter would be one argument away from
 * labelling the wrong cells.
 *
 * One label per cell and each centred over it, for the reason [SetLogHeader] gives at length: the
 * cells of this table centre what they hold, so a label at the left edge of the space they share
 * reads as a column that slipped.
 *
 * Hidden from semantics for the reason the workout's is: four words with nothing under them,
 * read out before every plan. Each field carries its own label.
 *
 * @param weightUnit the unit the weight column is typed in — the *draft's* unit, not the
 *   ambient one, since a template being edited is parsed back with the unit it was opened in.
 */
@Composable
fun PlannedSetHeader(
    weightUnit: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .padding(horizontal = OpenGymTheme.spacing.xs, vertical = OpenGymTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(
            text = stringResource(R.string.set_log_column_set),
            textAlign = TextAlign.Center,
            // The leading cell's clearance, so the label sits over the picker — see SetLogRow.
            modifier = Modifier
                .padding(end = INDEX_CLEARANCE)
                .width(INDEX_COLUMN_WIDTH),
        )
        Eyebrow(
            text = weightUnit,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(WEIGHT_COLUMN_WEIGHT),
        )
        Eyebrow(
            text = stringResource(R.string.set_log_column_reps),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(REPS_COLUMN_WEIGHT),
        )
    }
}
