/*
 * The table's cells are one small composable each, on purpose: the grid is the contract between
 * the row with fields and the row that is a record, and a column drawn twice is a column that
 * drifts. Counting them is not a measure of anything.
 */
@file:Suppress("TooManyFunctions")

package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ActiveSet
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.ui.R
import com.titan.core.ui.format.DistanceUnit
import com.titan.core.ui.format.distanceEntryUnit
import com.titan.core.ui.format.formatRpe
import com.titan.core.ui.format.formatSet
import com.titan.core.ui.format.formatSetCompact
import com.titan.core.ui.format.formatWeightValue
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme

/*
 * One set, as a row in the table under an exercise.
 *
 * Two composables, because a set being written and a set being read back are different jobs:
 *
 * - [SetLogRow] is the row with fields in it. Weight and reps are *text*, not numbers, and that
 *   is deliberate — see the note on its parameters. It draws the set being typed *and* every set
 *   already logged against an exercise the user can still change: there is no third component
 *   for "a logged set opened for correction", because a logged set is never closed. See
 *   [onCommit].
 * - [LoggedSetRow] is the record: a domain set, formatted, that cannot be typed in. What a table
 *   nobody can write to is made of — a finished workout's recap, a preview.
 *
 * They share a column grid so a screen can put one above the other — a summary listing
 * finished sets above the one still being worked — and have the digits line up. [SetLogHeader]
 * draws the same grid as labels.
 */

/**
 * The entry row: `1 | 80×5 | [80] [5] | 8 | ✓`.
 *
 * ## Why weight and reps are `String`
 *
 * Everything else in this module takes a domain type. A field being typed into cannot: `8.`
 * and `` and `0.` are all states a user passes through on the way to a number, and none of
 * them is one. The screen holds the text, [com.titan.core.ui.format.parseWeightMilligrams]
 * turns it into milligrams when it parses, and [com.titan.core.domain.validation.SetValidator]
 * says whether the result is allowed — the split CONTRIBUTING.md § Using the design system
 * describes. A row that took `Long?` would have to invent a rule for what to show while the
 * value is un-parseable, and every such rule eats the user's keystrokes.
 *
 * @param setNumber the set's position under its exercise, from 1. Not taken from the set
 *   itself: [ActiveSet] deliberately has no number, because a list is already ordered.
 * @param weight the weight field's contents, in the ambient [LocalUnitSystem]'s unit.
 * @param reps the rep field's contents.
 * @param setType what kind of set this is, drawn in the leading cell in place of the number.
 * @param onSetTypeChange the type picked from that cell's menu, or null for a row whose type
 *   cannot be changed. Null is what makes the cell a label rather than a control — see
 *   [SetTypePicker].
 * @param rpe the stored RPE (x 10), or null.
 * @param onRpeChange the rating picked from the cell's own menu, or null for a row whose rating
 *   cannot be changed. The scale is a closed vocabulary of nine and a text field for it would
 *   accept `47`, which is why the cell opens a menu rather than a keyboard — see [RpePicker].
 * @param previous what was done on this set last time, or null for a row with no history —
 *   which still reserves the column, so the rows of one table line up. A [PreviousSet] rather
 *   than the text of one, because the cell renders it twice: see [PreviousCell]. Which "last
 *   time" this is stays the caller's question — see `SessionRepository.getPreviousSets`.
 * @param showPrevious false drops the previous column entirely. A whole-table decision, not a
 *   per-row one: pass the same value to every row and to [SetLogHeader], or the labels stop
 *   sitting over the cells they name.
 * @param error the reason the row is refused, shown under it and announced on the fields it
 *   refused. Null when the row is fine.
 * @param isActive marks the set the user is on. Tints the row, so a glance mid-session finds
 *   it without reading.
 * @param enabled false makes the fields read-only without changing what they show — a
 *   session that has been finished, a set being written.
 * @param canComplete whether the tick can be pressed, which is a narrower question than
 *   [enabled]: a row can be perfectly writable and still not hold a set yet — the dimension its
 *   exercise is logged in may not be filled in — and a tick that is live for a set the write
 *   path refuses is the one thing this row must not do. The fields stay live either way, since
 *   filling them in is the way out of it.
 * @param target what the next-load engine says this set should be, or null for a row with
 *   nothing to suggest — an exercise never performed, a logged set, timed work. Drawn twice
 *   and spoken once: the weight and the reps show as the fields' placeholders while they are
 *   empty, and [SetTargetLine] sits under the row with the reason, as the one button that
 *   fills them in. See that component for the whole of it. Typing anything replaces the
 *   placeholder as it would in any field, and nothing about the tick changes — a target is
 *   never a set until the lifter says so.
 * @param onFillTarget put [target]'s figures into the fields. Required with a target, since
 *   the line is a button; ignored without one.
 * @param exerciseType how the exercise is logged, which decides the two value fields (#M7-17):
 *   weight and reps for [ExerciseType.STRENGTH], distance and time for
 *   [ExerciseType.ENDURANCE], time and an added weight for [ExerciseType.ISOMETRIC]. Two fields
 *   whatever the mode, in the same two cells, so a table of runs lines up under a table of
 *   presses and every grid constant below holds for all three. See [SetValueFields].
 * @param duration the time field's contents, `M:SS` or `H:MM:SS` — text, for [weight]'s reason.
 *   Drawn only by the timed modes.
 * @param distance the distance field's contents, in [distanceUnit]. Drawn only by
 *   [ExerciseType.ENDURANCE].
 * @param distanceUnit the unit the distance field is typed in: the ambient system's
 *   `distanceEntryUnit`, the one the caller parses with, as [weight] is typed in its weight unit.
 * @param footer drawn under the row, below the target line — the hold stopwatch, on the row a
 *   hold is typed into. Null for nothing.
 * @param menuAction one more item for the set-number menu, under the set types — the climb and
 *   steps of an endurance set. Null offers the types alone. See [SetMenuAction].
 * @param onCommit the fields have been left: focus has gone from the row altogether, or the
 *   keyboard's DONE was pressed. Null for a row whose text is not written until something else
 *   says so — the entry row, whose tick is that something.
 *
 *   This is what a set already logged is corrected through. There is no save button and no
 *   cancel, because there is no panel to have them: the row *is* the editor, it is live from the
 *   moment the table is drawn, and a correction is written when the user looks away from it. The
 *   two closed vocabularies are not covered by this and do not need to be — a rating and a set
 *   type are picked from a menu, which is an answer rather than a keystroke, so their callbacks
 *   fire once and the caller can write on the spot.
 */
@Composable
fun SetLogRow(
    setNumber: Int,
    weight: String,
    reps: String,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onCompletedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    exerciseType: ExerciseType = ExerciseType.STRENGTH,
    duration: String = "",
    onDurationChange: (String) -> Unit = {},
    distance: String = "",
    onDistanceChange: (String) -> Unit = {},
    distanceUnit: DistanceUnit = LocalUnitSystem.current.distanceEntryUnit,
    setType: SetType = SetType.NORMAL,
    onSetTypeChange: ((SetType) -> Unit)? = null,
    rpe: Int? = null,
    onRpeChange: ((Int?) -> Unit)? = null,
    previous: PreviousSet? = null,
    showPrevious: Boolean = true,
    isCompleted: Boolean = false,
    isActive: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    canComplete: Boolean = true,
    target: LoadSuggestion? = null,
    onFillTarget: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    onCommit: (() -> Unit)? = null,
    menuAction: SetMenuAction? = null,
) {
    val focusManager = LocalFocusManager.current
    // Cleared on DONE so the key that *looks* like it finishes the row actually does: dismissing
    // the keyboard leaves the field focused, and a correction nobody committed is a correction
    // the user believes they made. Clearing focus is the same event as tapping away, so both
    // ways out arrive here as one.
    val onDone = onCommit?.let { { focusManager.clearFocus() } }

    SetRowFrame(
        // Watched on the frame rather than on each field, so moving between the weight and the
        // reps of one row is not a write: what has been left is the row, and the row is what
        // gets written.
        modifier = modifier.commitOnFocusLoss(onCommit),
        isActive = isActive,
        isCompleted = isCompleted,
        error = error,
        footer = if (target == null && footer == null) {
            null
        } else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs)) {
                    if (target != null) {
                        SetTargetLine(
                            target = target,
                            onFill = onFillTarget ?: {},
                            // The line sits on the row's own ground, and the entry row's is the
                            // tint: its ink is the one the contrast test holds against that tint.
                            color = if (isActive) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            enabled = enabled && onFillTarget != null,
                        )
                    }
                    footer?.invoke()
                }
            }
        },
    ) {
        SetTypeCell(
            setNumber = setNumber,
            setType = setType,
            onSetTypeChange = onSetTypeChange,
            isActive = isActive,
            enabled = enabled,
            extraAction = menuAction,
        )
        PreviousCell(previous = previous, showPrevious = showPrevious)

        SetValueFields(
            exerciseType = exerciseType,
            setNumber = setNumber,
            values = SetFieldValues(weight = weight, reps = reps, duration = duration, distance = distance),
            onWeightChange = onWeightChange,
            onRepsChange = onRepsChange,
            onDurationChange = onDurationChange,
            onDistanceChange = onDistanceChange,
            distanceUnit = distanceUnit,
            enabled = enabled,
            error = error,
            // The figures without their unit, since the column's header carries that — and in
            // the form the field will hold once it is typed, so the tap and the keystroke
            // produce the same text. Only a strength set has a target: see `NextLoadEngine`.
            weightPlaceholder = target?.weight?.let { formatWeightValue(it) },
            repsPlaceholder = target?.reps?.toString(),
            onDone = onDone,
        )

        RpeValueCell(
            setNumber = setNumber,
            rpe = rpe,
            onRpeChange = onRpeChange,
            enabled = enabled,
        )
        CompletionToggle(
            setNumber = setNumber,
            isCompleted = isCompleted,
            onCompletedChange = onCompletedChange,
            enabled = enabled && canComplete,
        )
    }
}

/**
 * The same row, read-only, from a set that has already been written.
 *
 * The weight and reps collapse into one cell — `80 kg × 5` — because nothing here is being
 * typed and two fixed columns for two values that are always read together is width spent on
 * a grid nobody is filling in.
 *
 * There is nothing to tap. A logged set is corrected in the row it is already in, by typing into
 * it — that is [SetLogRow], which is what a screen that can still be written to draws for every
 * set in the table. This one is for the tables that cannot: a finished session's recap, a
 * preview, a template's plan.
 *
 * @param onCompletedChange takes the tick off the set, or puts it back. Null draws the tick as
 *   the plain mark it used to be, for a table that cannot be written to.
 */
@Composable
fun LoggedSetRow(
    setNumber: Int,
    set: WorkoutSet,
    modifier: Modifier = Modifier,
    previous: PreviousSet? = null,
    showPrevious: Boolean = true,
    onCompletedChange: ((Boolean) -> Unit)? = null,
) {
    LoggedSetRow(
        setNumber = setNumber,
        setType = set.setType,
        performed = formatSet(set.weight, set.reps, set.duration, set.distance),
        rpe = set.rpe,
        isCompleted = set.isCompleted,
        previous = previous,
        showPrevious = showPrevious,
        onCompletedChange = onCompletedChange,
        modifier = modifier,
    )
}

/** The same, for a set of a session still in progress. */
@Composable
fun LoggedSetRow(
    setNumber: Int,
    set: ActiveSet,
    modifier: Modifier = Modifier,
    previous: PreviousSet? = null,
    showPrevious: Boolean = true,
    onCompletedChange: ((Boolean) -> Unit)? = null,
) {
    LoggedSetRow(
        setNumber = setNumber,
        setType = set.setType,
        performed = formatSet(set.weight, set.reps, set.duration, set.distance),
        rpe = set.rpe,
        isCompleted = set.isCompleted,
        previous = previous,
        showPrevious = showPrevious,
        onCompletedChange = onCompletedChange,
        modifier = modifier,
    )
}

/**
 * The column labels: `SET`, `PREV`, `KG`, `REPS`, `RPE`.
 *
 * Drawn as an eyebrow rather than as a table header, because there is no table — the rows
 * are a `Column` and the columns line up because both use these widths. Reading the header
 * out to a screen reader would then announce five words with nothing under them, so it is
 * hidden from semantics; each field carries its own label instead.
 *
 * ### One label per cell, on the cell's own grid
 *
 * The two figure columns used to share a single `KG × REPS` label spanning both of them, which
 * put the words at the *left edge* of a span whose contents — every cell in this table centres
 * what it holds — sat in the middle of it. The header read as though it had slipped a column.
 *
 * So the header is built from the same cells the rows are, in the same order and at the same
 * widths, each label centred the way the value under it is. The `×` that used to sit between
 * the two fields is gone from both — see the note on the columns for what its 18dp bought.
 *
 * @param showPrevious must match what the rows under it were given, since nothing here can
 *   check: a header that keeps a column the rows dropped is labels sitting over the wrong
 *   cells. [setTableFitsPrevious] is the one answer both should take.
 * @param exerciseType and [distanceUnit] must match the rows' for the same reason: they decide
 *   which two value columns there are — `KG REPS`, `KM TIME` or `TIME KG`. See [SetValueFields].
 */
@Composable
fun SetLogHeader(
    modifier: Modifier = Modifier,
    showPrevious: Boolean = true,
    exerciseType: ExerciseType = ExerciseType.STRENGTH,
    distanceUnit: DistanceUnit = LocalUnitSystem.current.distanceEntryUnit,
) {
    val weightUnit = LocalUnitSystem.current.weightSuffix

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .padding(horizontal = TitanTheme.spacing.xs, vertical = TitanTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(
            text = stringResource(R.string.set_log_column_set),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(end = INDEX_CLEARANCE)
                .width(INDEX_COLUMN_WIDTH),
        )
        if (showPrevious) {
            Eyebrow(
                text = stringResource(R.string.set_log_column_previous),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(PREVIOUS_COLUMN_WIDTH),
            )
        }
        SetValueLabels(exerciseType = exerciseType, weightUnit = weightUnit, distanceUnit = distanceUnit)
        Eyebrow(
            text = stringResource(R.string.set_log_column_rpe),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(end = TOGGLE_CLEARANCE)
                .width(RPE_COLUMN_WIDTH),
        )
        Box(modifier = Modifier.width(TOGGLE_SIZE))
    }
}

// ─── Cells ──────────────────────────────────────────────────────────────────────────────

/**
 * The ground a set row sits on, its padding, and what goes under it: the error, and the
 * [footer] a row may carry — the target line, on the row being typed.
 *
 * Internal rather than private because both rows in this file are it: the row with fields and the
 * row that is a record draw the same cells on the same grid, and a frame each would be two sets
 * of paddings that drift.
 */
@Composable
internal fun SetRowFrame(
    modifier: Modifier,
    isActive: Boolean,
    isCompleted: Boolean,
    error: String?,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    // Three grounds, in order of how much they are asking for: the set being worked is
    // tinted, a finished set is left on the card's own surface, and an untouched one sits on
    // the faintest fill so the block of rows still reads as a table.
    val background: Color = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        isCompleted -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = PENDING_ROW_ALPHA)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            // `xs` at the sides, not the `md` the header used to share: the row's width is spent
            // on the controls' tap targets instead — see the note on the columns below — and
            // the card's own padding is what keeps the table off the card's edge.
            .padding(horizontal = TitanTheme.spacing.xs, vertical = TitanTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // A row of fields is 40dp of field and needs no floor; a read-only row is 20dp
                // of text, and the tick on it is owed 48 — the padding above and below provides
                // the rest. Without this the tick on a logged set is announced 45dp tall.
                .heightIn(min = MIN_TOUCH_TARGET - TitanTheme.spacing.sm * 2),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = TitanTheme.spacing.xs),
            )
        }
        // Under the error, not above it: a refusal is about what is in the fields now, and
        // the target is about what could be, so the one nearer the fields is the one about them.
        if (footer != null) {
            Box(modifier = Modifier.padding(top = TitanTheme.spacing.xs)) {
                footer()
            }
        }
    }
}

/**
 * The leading cell, as a label: the set's number, or the initial of its type.
 *
 * What it draws is [SetMarker]'s, including which types are tinted and why. What is added here is
 * the column's width and what TalkBack hears — "Warm-up, set 1", both halves, since the letter
 * alone is not a word and the number it replaced is still the answer to "which set".
 */
@Composable
internal fun SetIndexCell(
    setNumber: Int,
    setType: SetType,
    isActive: Boolean,
) {
    val spoken = stringResource(R.string.set_log_position, setType.label(), setNumber)

    SetMarker(
        setNumber = setNumber,
        setType = setType,
        isActive = isActive,
        // The clearance even though nothing here can be tapped: the read-only cell has to land
        // on the picker's column, or a paused workout shifts every figure in the table.
        modifier = Modifier
            .padding(end = INDEX_CLEARANCE)
            .width(INDEX_COLUMN_WIDTH)
            .clearAndSetSemantics { contentDescription = spoken },
    )
}

/**
 * The leading cell of a row whose type can be changed, or of one whose cannot.
 *
 * The branch is here rather than at each call site because a row has to draw the *same* cell
 * either way: a table whose leading column changed width when a session was paused would relayout
 * every figure in it.
 */
@Composable
internal fun SetTypeCell(
    setNumber: Int,
    setType: SetType,
    onSetTypeChange: ((SetType) -> Unit)?,
    isActive: Boolean,
    enabled: Boolean,
    extraAction: SetMenuAction? = null,
) {
    if (onSetTypeChange == null) {
        SetIndexCell(setNumber = setNumber, setType = setType, isActive = isActive)
        return
    }

    SetTypePicker(
        setNumber = setNumber,
        selected = setType,
        onSelect = onSetTypeChange,
        isActive = isActive,
        enabled = enabled,
        modifier = Modifier.padding(end = INDEX_CLEARANCE),
        extraAction = extraAction,
    )
}

/**
 * The rating cell of a row that can be rated, or of one that can only be read.
 *
 * [SetTypeCell]'s counterpart, and the same rule: the read-only cell and the menu's anchor are the
 * same width, so pausing a session does not move the columns of the table it is drawn in.
 */
@Composable
internal fun RpeValueCell(
    setNumber: Int,
    rpe: Int?,
    onRpeChange: ((Int?) -> Unit)?,
    enabled: Boolean,
) {
    if (onRpeChange == null) {
        RpeCell(setNumber = setNumber, rpe = rpe)
        return
    }

    RpePicker(
        setNumber = setNumber,
        rpe = rpe,
        onRpeChange = onRpeChange,
        enabled = enabled,
        modifier = Modifier.padding(end = TOGGLE_CLEARANCE),
    )
}

/**
 * What was done last time, or a dash holding the column open.
 *
 * The dash is the point. A set with no history is the ordinary case in a table that has some —
 * last session was three sets, this one is five — and a cell that collapsed would shift every
 * cell to its right by the column's width, so two rows of one table would not line up.
 *
 * The two renderings are why this takes a [PreviousSet] rather than the text of one. What the
 * cell *draws* has to survive 52dp between two fields, so it is `100×5` with no unit; what a
 * screen reader is *given* has all the room a sentence needs, so it is `Previously 100 kg × 5`.
 * A caller handing over a string could only supply one of them, and whichever it chose would be
 * wrong for the other reader.
 */
@Composable
internal fun PreviousCell(
    previous: PreviousSet?,
    showPrevious: Boolean,
) {
    if (!showPrevious) return
    val placeholder = stringResource(R.string.set_log_previous_none)
    val compact = previous?.let { formatSetCompact(it.weight, it.reps, it.duration, it.distance) }
    val spoken = previous
        ?.let { formatSet(it.weight, it.reps, it.duration, it.distance) }
        ?.let { stringResource(R.string.set_log_previous_value, it) }

    Text(
        text = compact ?: placeholder,
        style = TitanTheme.typography.numericSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        // Ellipsis rather than the default clip, because the column is a fixed width in `dp`
        // and its text is in `sp`: at a large system font scale, or for a metric-entered
        // weight read in pounds (`132.28×10`), the value is wider than the cell. Clipped, that
        // renders as `132.28×1` — a different set, and a plausible one. Ellipsised it renders
        // as a value that visibly did not fit, which is the only honest thing a cell this
        // narrow can say.
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(PREVIOUS_COLUMN_WIDTH)
            // Replaced rather than added to: read as text the cell is four unlabelled
            // characters between a set number and a weight field, and a screen reader user
            // has no column headers to tell them which of the three it was. With nothing to
            // report it leaves the tree altogether — the dash is spacing, not a value.
            .clearAndSetSemantics { if (spoken != null) contentDescription = spoken },
    )
}

/**
 * The RPE as a figure, or a dash for one that was never given.
 *
 * Read-only: a cell that can be *changed* is [RpePicker], which draws the same figure at the same
 * width with a menu under it. The description carries the value — "…, set 3, 8.5" — because a
 * content description *replaces* the text of the node it is set on rather than adding to it. A
 * description of the column alone would announce what the cell is and never what it holds, which
 * is the one thing a reader wanted.
 */
@Composable
internal fun RpeCell(
    setNumber: Int,
    rpe: Int?,
) {
    val unset = stringResource(R.string.set_log_rpe_unset)
    val spoken = if (rpe != null) {
        stringResource(R.string.set_log_rpe_value, setNumber, formatRpe(rpe))
    } else {
        stringResource(R.string.set_log_rpe_none, setNumber)
    }

    Text(
        text = rpe?.let(::formatRpe) ?: unset,
        style = TitanTheme.typography.numericSmall,
        // The same ink whether the cell holds a figure or the dash for none. `outline` was
        // the dash's colour, and on the tinted ground of a pending row it is under 4.5:1 —
        // see `RpePicker` for the same finding on the cell that can be tapped.
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .padding(end = TOGGLE_CLEARANCE)
            .width(RPE_COLUMN_WIDTH)
            .semantics { contentDescription = spoken },
    )
}

/**
 * The tick.
 *
 * A real toggle rather than a tinted glyph: it is the control the whole row exists to reach,
 * it has to announce its checked state, and `IconToggleButton` is what gives it a 48dp touch
 * target regardless of the 32dp it is drawn at — given the room, which the grid's clearances
 * are; see the note on the columns.
 *
 * What it announces is a **label and a state**, separately: "Completed, Set 3, checkbox" — the
 * state in the app's own words as a `stateDescription`, rather than a description that changes
 * with the state ("Mark set 3 done" / "Set 3 is done") and leaves the reader to work out which
 * of the two the checkbox is currently in. The same two words on every tick, and a test can
 * assert the state without knowing the phrasing.
 *
 * Shared with [LoggedSetRow], where the same control takes the tick back *off* a set — the same
 * glyph, the same place in the row, and the same two states, because it is the same question asked
 * of a set that has been written rather than of one that has not.
 */
@Composable
internal fun CompletionToggle(
    setNumber: Int,
    isCompleted: Boolean,
    onCompletedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    val spoken = stringResource(R.string.set_log_completion, setNumber)
    val state = stringResource(if (isCompleted) R.string.set_log_completed else R.string.set_log_not_completed)

    IconToggleButton(
        checked = isCompleted,
        onCheckedChange = onCompletedChange,
        enabled = enabled,
        colors = IconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.outline,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
            checkedContainerColor = Color.Transparent,
            checkedContentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier
            .size(TOGGLE_SIZE)
            .semantics {
                contentDescription = spoken
                stateDescription = state
            },
    ) {
        Icon(imageVector = Icons.Filled.Check, contentDescription = null)
    }
}

@Composable
@Suppress("LongParameterList")
private fun LoggedSetRow(
    setNumber: Int,
    setType: SetType,
    performed: String?,
    rpe: Int?,
    isCompleted: Boolean,
    previous: PreviousSet?,
    showPrevious: Boolean,
    onCompletedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
) {
    // The dash is this row's answer to an empty set rather than the formatter's: a table has
    // columns to keep lined up, and a sentence quoting the same set would want the clause gone.
    val shown = performed ?: stringResource(R.string.set_log_rpe_unset)

    SetRowFrame(
        modifier = modifier,
        isActive = false,
        isCompleted = isCompleted,
        error = null,
    ) {
        // Kept as a group of its own even with nothing to tap, because the grid depends on it:
        // the group takes the width the tick does not, which is what lands its cells on exactly
        // the columns a row of fields puts them on. See the note on the columns below.
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SetIndexCell(setNumber = setNumber, setType = setType, isActive = false)
            PreviousCell(previous = previous, showPrevious = showPrevious)
            Text(
                text = shown,
                style = TitanTheme.typography.numeric,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            RpeCell(setNumber = setNumber, rpe = rpe)
        }

        if (onCompletedChange != null) {
            CompletionToggle(
                setNumber = setNumber,
                isCompleted = isCompleted,
                onCompletedChange = onCompletedChange,
                enabled = true,
            )
        } else {
            Box(modifier = Modifier.size(TOGGLE_SIZE), contentAlignment = Alignment.Center) {
                if (isCompleted) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Calls [onCommit] when focus leaves the subtree this is applied to, and not before.
 *
 * The guard is the whole of it. `onFocusChanged` fires once on composition with nothing focused,
 * so a naive "not focused, therefore left" writes every row of the table the first time the
 * screen is drawn — and again on every recomposition that remounts one. What is wanted is the
 * *edge*: focus was in here, and now it is not.
 *
 * The `focusGroup` under it is what makes "the subtree" a thing to ask about at all. Without it
 * the row has two independent focus targets below one observer, which reports each of them in
 * turn — so moving from the weight to the reps would read as a departure and write the row twice
 * on the way across it. Grouped, the observer sees one state, and `hasFocus` is true for as long
 * as the focus is anywhere in the row.
 */
@Composable
private fun Modifier.commitOnFocusLoss(onCommit: (() -> Unit)?): Modifier {
    // Remembered above the null check, not below it. A `remember` behind an early return claims a
    // slot on some compositions and not others, so the first caller to make `onCommit` conditional
    // would leave this reading whatever composable took the slot instead.
    var wasFocused by remember { mutableStateOf(false) }
    if (onCommit == null) return this

    return onFocusChanged { state ->
        if (wasFocused && !state.hasFocus) onCommit()
        wasFocused = state.hasFocus
    }.focusGroup()
}

/**
 * What was done on one set of one exercise the last time it was performed.
 *
 * The quantities rather than the line they are drawn on, for the reason every other value type
 * crossing into composition carries quantities: the unit is the ambient [LocalUnitSystem] and the
 * `×` is a resource, so a caller that formatted would be picking both on behalf of a screen it
 * cannot see. It is also what lets the cell render the same fact twice — compact for the column,
 * spelled out for a screen reader — which a string could not.
 *
 * Both fields are nullable and independently so, matching `WorkoutSet`: a bodyweight set has reps
 * and no weight, and a set logged in a dimension this table has no column for has neither. A
 * value with neither draws as though there were no history at all, which is what it amounts to.
 *
 * A timed set carries its time and its distance instead (#M7-17), so a plank's previous column
 * reads `1:30` rather than a dash: last time was a set, and the column comparing against it has
 * something to say. Which figure the narrow cell keeps is `formatSetCompact`'s rule.
 *
 * @param weight in milligrams, the canonical unit.
 * @param reps how many, or null for a set that recorded none.
 * @param duration in seconds, or null for a set that was not timed.
 * @param distance in centimetres, or null for a set that covered no ground.
 */
@Immutable
data class PreviousSet(
    val weight: Long?,
    val reps: Int?,
    val duration: Long? = null,
    val distance: Long? = null,
)

// ─── The grid ───────────────────────────────────────────────────────────────────────────

/*
 * The fixed columns, and the two that share what is left.
 *
 * Fixed for anything whose width is set by its content — a set number is one or two
 * characters, an RPE is at most four — and proportional for the fields, which are the only
 * cells that benefit from a wider screen. The weight field is the larger share because
 * `102.5` is five characters and a rep count is rarely more than two.
 *
 * **The fixed widths are sized against a 320dp screen**, which is the narrowest Android still
 * runs on, and they are tight there on purpose: the cell that has to survive is the value one,
 * because a logged set reading `60 kg` with the reps clipped off is a row that has lost the
 * only thing it was recording. Every dp taken off a fixed column goes there.
 *
 * ### The tap targets, and where their room comes from
 *
 * Five of the cells are *controls* on a row being typed — the set type's menu, the two fields,
 * the rating's menu and the tick — and each is owed 48dp (`MIN_TOUCH_TARGET`), which the build
 * checks: `AccessibleComposePreviewTester` fails on a control announced under it. Compose grows
 * a small target into the room around it, but only into room no neighbour holds, and the
 * neighbours here are a value that speaks and a field that can be typed in. So the room is made
 * explicitly, and it is paid for three ways:
 *
 * - **Two clearances** alongside the grid's 4dp gap. [INDEX_CLEARANCE] after the leading cell,
 *   which grows 10dp each way and would otherwise stop at the previous-set column;
 *   [TOGGLE_CLEARANCE] between the rating and the tick, because two controls in a row are
 *   settled right to left, so the tick takes its 8dp of the gap first and the rating needs 8dp
 *   of its own after that. Clearances rather than wider cells because they cost less: a 40dp
 *   cell with 4dp gaps and a 28dp cell with a 10dp gap are announced the same, and the second
 *   leaves 6dp to the fields.
 * - **The `×` between the fields is gone.** It was 18dp with its gaps, on a 360dp phone the
 *   difference between a reps field announced at 46dp and one at 50, and the header already
 *   says what each column is.
 * - **The previous-set column gives way** where even that is not enough: [setTableFitsPrevious]
 *   says whether a table of a given width can carry it and still hand both fields a tap target,
 *   and every table asks it once and passes the answer to its header and every row. On a 320dp
 *   phone the column goes; on 360dp and up it stays.
 *
 * The header, [PlannedSetRow] and the read-only row all carry the same clearances on the same
 * cells, which is what keeps their columns on one grid.
 *
 * [PREVIOUS_COLUMN_WIDTH] is the one that grew rather than shrank, and it is the exception that
 * proves the rule above: it held a dash until the previous session was actually read (#101), and
 * a column that now carries `102.5×10` cannot be sized for `–`. Eight characters of the
 * `numericSmall` role is what it is set against, which covers the heaviest lift anybody logs in
 * a compound; past that the cell clips, and what it clips is the rep count of a set performed
 * last week rather than anything on today's row.
 *
 * A logged row groups every cell except the tick under one parent, and the grid survives that
 * because the group takes the width the tick does not: with one gap between them, the group's own
 * cells land on exactly the columns a flat row puts them on.
 */

internal val INDEX_COLUMN_WIDTH: Dp = 28.dp
internal val PREVIOUS_COLUMN_WIDTH: Dp = 52.dp
internal val RPE_COLUMN_WIDTH: Dp = 32.dp
internal val TOGGLE_SIZE: Dp = 32.dp

/** Room after the leading cell, on top of the grid's gap — see the note on the columns above. */
internal val INDEX_CLEARANCE: Dp = 6.dp

/** Room between the rating cell and the tick, on top of the grid's gap. */
internal val TOGGLE_CLEARANCE: Dp = 12.dp

/**
 * The two fields' shares of what the fixed columns leave. The weight gets the larger because
 * `102.5` is five characters and a rep count is rarely more than two — and no more than this,
 * because the reps field has to reach 48dp on a 360dp phone with the previous column still up,
 * where the two of them have 106dp between them: 1.1 asks 101 of it and leaves the column a
 * margin, 1.3 would ask 110 and lose it.
 */
internal const val WEIGHT_COLUMN_WEIGHT = 1.1f
internal const val REPS_COLUMN_WEIGHT = 1f

/**
 * Whether a set table [tableWidth] wide can carry the previous-set column and still give both
 * fields a 48dp tap target — the one answer a table passes to its header and every row as
 * `showPrevious`, so the columns of all of them move together.
 *
 * Arithmetic over the grid's own constants rather than a screen-width threshold, so it stays
 * right when a column, a clearance or the card's padding changes. [tableWidth] is the width
 * the table is given, frame padding included — `BoxWithConstraints.maxWidth` at the call site.
 */
@Composable
fun setTableFitsPrevious(tableWidth: Dp): Boolean {
    val gap = TitanTheme.spacing.xs
    // Everything in a row of fields except the two fields: the frame's side padding, the five
    // fixed cells with their clearances, and the six gaps between seven children.
    val fixed = TitanTheme.spacing.xs * 2 +
        INDEX_COLUMN_WIDTH + INDEX_CLEARANCE +
        PREVIOUS_COLUMN_WIDTH +
        RPE_COLUMN_WIDTH + TOGGLE_CLEARANCE +
        TOGGLE_SIZE +
        gap * 6
    // The reps field is the smaller share, so it is the one that has to clear 48dp.
    val fieldsNeeded = MIN_TOUCH_TARGET * ((WEIGHT_COLUMN_WEIGHT + REPS_COLUMN_WEIGHT) / REPS_COLUMN_WEIGHT)
    return tableWidth - fixed >= fieldsNeeded
}

/**
 * How much of the fill tone an unlogged row keeps.
 *
 * At full strength the pending rows are as loud as the active one and the table stops having
 * a "here"; at zero the rows lose their edges and the block reads as one paragraph of digits.
 */
private const val PENDING_ROW_ALPHA = 0.5f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Set log - logging a set", widthDp = 360)
@Composable
fun SetLogRowPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        SetLogTable()
    }
}

@Preview(name = "Set log - logging a set, dark", widthDp = 360)
@Composable
fun SetLogRowDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        SetLogTable()
    }
}

@Preview(name = "Set log - a refused set", widthDp = 360)
@Composable
fun SetLogRowInvalidPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TitanCard(modifier = Modifier.fillMaxWidth().padding(TitanTheme.spacing.screen)) {
            SetLogHeader()
            HairlineDivider()
            SetLogRow(
                setNumber = 4,
                weight = "80",
                reps = "0",
                onWeightChange = {},
                onRepsChange = {},
                onCompletedChange = {},
                onSetTypeChange = {},
                onRpeChange = {},
                previous = PreviousSet(weight = 75_000_000, reps = 6),
                isActive = true,
                error = "reps must be within 1..10,000, was 0",
            )
        }
    }
}

@Preview(name = "Set log - a table being corrected", widthDp = 360)
@Composable
fun SetLogCorrectionPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        CorrectableTable()
    }
}

@Preview(name = "Set log - a table being corrected, dark", widthDp = 360)
@Composable
fun SetLogCorrectionDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        CorrectableTable()
    }
}

@Preview(name = "Set log - a hold", widthDp = 360)
@Composable
fun SetLogHoldPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TimedTable(exerciseType = ExerciseType.ISOMETRIC)
    }
}

@Preview(name = "Set log - a run, dark", widthDp = 360)
@Composable
fun SetLogRunDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        TimedTable(exerciseType = ExerciseType.ENDURANCE)
    }
}

/**
 * A timed exercise's table (#M7-17): a logged set, then the row being typed — a hold's time and
 * its added weight, or a run's distance and time — with the stopwatch under a hold's.
 */
@Composable
private fun TimedTable(exerciseType: ExerciseType) {
    val isHold = exerciseType == ExerciseType.ISOMETRIC
    TitanCard(modifier = Modifier.fillMaxWidth().padding(TitanTheme.spacing.screen)) {
        SetLogHeader(exerciseType = exerciseType)
        HairlineDivider()
        LoggedSetRow(
            setNumber = 1,
            set = if (isHold) {
                ActiveSet(duration = 90, weight = 10_000_000, isCompleted = true, completedAt = 1)
            } else {
                ActiveSet(duration = 1_634, distance = 500_000, rpe = 70, isCompleted = true, completedAt = 1)
            },
            previous = if (isHold) PreviousSet(weight = null, reps = null, duration = 75) else null,
            onCompletedChange = {},
        )
        HairlineDivider()
        SetLogRow(
            setNumber = 2,
            weight = "",
            reps = "",
            onWeightChange = {},
            onRepsChange = {},
            onCompletedChange = {},
            exerciseType = exerciseType,
            duration = if (isHold) "" else "26:50",
            distance = if (isHold) "" else "5",
            onSetTypeChange = {},
            onRpeChange = {},
            previous = if (isHold) PreviousSet(weight = null, reps = null, duration = 80) else null,
            isActive = true,
            canComplete = !isHold,
            footer = if (isHold) {
                { HoldStopwatch(setNumber = 2, elapsed = null, onStart = {}, onStop = {}) }
            } else {
                null
            },
        )
    }
}

/**
 * What an exercise of a running workout looks like: every row live, the next one accented.
 *
 * The point of the picture is that the second set — the one being corrected from 70 to 72.5 —
 * is not a mode. It is the same row it was a moment ago, with the sets above and below it still
 * on screen, which is exactly what a mistyped weight is checked against.
 */
@Composable
private fun CorrectableTable() {
    TitanCard(modifier = Modifier.fillMaxWidth().padding(TitanTheme.spacing.screen)) {
        SetLogHeader()
        HairlineDivider()
        LiveRow(setNumber = 1, weight = "60", reps = "10", rpe = 60, setType = SetType.WARMUP)
        HairlineDivider()
        LiveRow(setNumber = 2, weight = "72.5", reps = "8", rpe = 75)
        HairlineDivider()
        LiveRow(setNumber = 3, weight = "75", reps = "6", rpe = 85)
        HairlineDivider()
        SetLogRow(
            setNumber = 4,
            weight = "",
            reps = "",
            onWeightChange = {},
            onRepsChange = {},
            onCompletedChange = {},
            onSetTypeChange = {},
            onRpeChange = {},
            previous = PreviousSet(weight = 75_000_000, reps = 6),
            isActive = true,
            // What the engine makes of 75 × 6 under the strength range: the top was hit, so
            // the step goes on and the reps drop back — shown in the empty fields and said
            // under the row. See [SetTargetLine].
            target = LoadSuggestion(
                weight = 77_500_000,
                reps = 3,
                reason = SuggestionReason.INCREASE,
                previousWeight = 75_000_000,
                basis = PastPerformance(
                    listOf(
                        WorkoutSet(
                            sessionExerciseId = 1,
                            setNumber = 1,
                            reps = 6,
                            weight = 75_000_000,
                            isCompleted = true,
                            completedAt = 0,
                            createdAt = 0,
                            updatedAt = 0,
                        ),
                    ),
                ),
            ),
            onFillTarget = {},
        )
    }
}

/** One set already logged, still typeable. `onCommit` is what writes it — see [SetLogRow]. */
@Composable
private fun LiveRow(
    setNumber: Int,
    weight: String,
    reps: String,
    rpe: Int,
    setType: SetType = SetType.NORMAL,
) {
    SetLogRow(
        setNumber = setNumber,
        weight = weight,
        reps = reps,
        onWeightChange = {},
        onRepsChange = {},
        onCompletedChange = {},
        setType = setType,
        onSetTypeChange = {},
        rpe = rpe,
        onRpeChange = {},
        previous = null,
        isCompleted = true,
        onCommit = {},
    )
}

@Composable
private fun SetLogTable() {
    TitanCard(modifier = Modifier.fillMaxWidth().padding(TitanTheme.spacing.screen)) {
        SetLogHeader()
        HairlineDivider()
        LoggedSetRow(
            setNumber = 1,
            set = ActiveSet(
                reps = 10,
                weight = 60_000_000,
                rpe = 60,
                setType = SetType.WARMUP,
                isCompleted = true,
                completedAt = 1,
            ),
            previous = PreviousSet(weight = 60_000_000, reps = 10),
            onCompletedChange = {},
        )
        HairlineDivider()
        // Ticked off again: the set is still in the table, under the number it had, and no longer
        // counts towards anything — see `MarkSetCompletedUseCase`.
        LoggedSetRow(
            setNumber = 2,
            set = ActiveSet(reps = 8, weight = 70_000_000, rpe = 75, isCompleted = false),
            previous = PreviousSet(weight = 70_000_000, reps = 8),
            onCompletedChange = {},
        )
        HairlineDivider()
        SetLogRow(
            setNumber = 3,
            weight = "80",
            reps = "5",
            onWeightChange = {},
            onRepsChange = {},
            onCompletedChange = {},
            onSetTypeChange = {},
            rpe = 85,
            onRpeChange = {},
            previous = PreviousSet(weight = 75_000_000, reps = 6),
            isActive = true,
        )
        HairlineDivider()
        SetLogRow(
            setNumber = 4,
            weight = "",
            reps = "",
            onWeightChange = {},
            onRepsChange = {},
            onCompletedChange = {},
            setType = SetType.DROP_SET,
            onSetTypeChange = {},
            onRpeChange = {},
            // No history: last session stopped at three sets. The column stays, holding the
            // fields under the ones above them.
            previous = null,
        )
    }
}
