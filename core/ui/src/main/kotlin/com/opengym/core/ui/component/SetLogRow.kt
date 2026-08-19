package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatRpe
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme

/*
 * One set, as a row in the table under an exercise.
 *
 * Two composables, because logging a set and reading one back are different jobs:
 *
 * - [SetLogRow] is the entry row. Weight and reps are *text*, not numbers, and that is
 *   deliberate — see the note on its parameters.
 * - [LoggedSetRow] is the record. It takes a domain set, formats it, and cannot be typed in.
 *
 * They share a column grid so a screen can put one above the other — a summary listing
 * finished sets above the one still being worked — and have the digits line up. [SetLogHeader]
 * draws the same grid as labels.
 */

/**
 * The entry row: `1 | 80×5 | [80] × [5] | 8 | ✓`.
 *
 * ## Why weight and reps are `String`
 *
 * Everything else in this module takes a domain type. A field being typed into cannot: `8.`
 * and `` and `0.` are all states a user passes through on the way to a number, and none of
 * them is one. The screen holds the text, [com.opengym.core.ui.format.parseWeightMilligrams]
 * turns it into milligrams when it parses, and [com.opengym.core.domain.validation.SetValidator]
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
 * @param previous what was done on this set last time — `80 kg × 5` — or null for a set with
 *   no history, which still reserves the column so the rows of one table line up. Formatted by
 *   the caller: which "last time" this is (#101) is the feature's question, not this row's.
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
    setType: SetType = SetType.NORMAL,
    onSetTypeChange: ((SetType) -> Unit)? = null,
    rpe: Int? = null,
    onRpeChange: ((Int?) -> Unit)? = null,
    previous: String? = null,
    showPrevious: Boolean = true,
    isCompleted: Boolean = false,
    isActive: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    canComplete: Boolean = true,
) {
    SetRowFrame(
        modifier = modifier,
        isActive = isActive,
        isCompleted = isCompleted,
        error = error,
    ) {
        SetTypeCell(
            setNumber = setNumber,
            setType = setType,
            onSetTypeChange = onSetTypeChange,
            isActive = isActive,
            enabled = enabled,
        )
        PreviousCell(previous = previous, showPrevious = showPrevious)

        SetValueField(
            value = weight,
            onValueChange = onWeightChange,
            description = stringResource(R.string.set_log_weight_field, setNumber),
            keyboardType = KeyboardType.Decimal,
            enabled = enabled,
            error = error,
            modifier = Modifier.weight(WEIGHT_COLUMN_WEIGHT),
        )
        Text(
            text = MULTIPLICATION_SIGN,
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Decoration between two labelled fields. Left in the tree it is a stop of its
            // own, and swiping through a five-set exercise hits "multiplication sign" five
            // times between the values it is punctuating.
            modifier = Modifier.clearAndSetSemantics { },
        )
        SetValueField(
            value = reps,
            onValueChange = onRepsChange,
            description = stringResource(R.string.set_log_reps_field, setNumber),
            keyboardType = KeyboardType.Number,
            enabled = enabled,
            error = error,
            modifier = Modifier.weight(REPS_COLUMN_WEIGHT),
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
 * @param onClick reopens the set for correction, or null for a table that cannot be edited — a
 *   finished session's recap, a preview. The cells are the affordance: a pencil per row would put
 *   a control on every line of a table whose whole job is to be read at a glance, and there is
 *   nothing else a tap on a logged set's values could mean. The tick is *not* part of that target
 *   — see [onCompletedChange].
 * @param onCompletedChange takes the tick off the set, or puts it back. Null draws the tick as
 *   the plain mark it used to be, for a table that cannot be written to.
 *
 *   Here rather than only in the editor because a tick pressed by accident is the commonest
 *   mistake a set row produces, and the correction for it should not be four taps deep: the
 *   control that made the mistake is the control that undoes it. An un-ticked set stays in the
 *   table, under the number it had, and stops counting — see `MarkSetCompletedUseCase`.
 */
@Composable
fun LoggedSetRow(
    setNumber: Int,
    set: WorkoutSet,
    modifier: Modifier = Modifier,
    previous: String? = null,
    showPrevious: Boolean = true,
    onClick: (() -> Unit)? = null,
    onCompletedChange: ((Boolean) -> Unit)? = null,
) {
    LoggedSetRow(
        setNumber = setNumber,
        setType = set.setType,
        weight = set.weight,
        reps = set.reps,
        rpe = set.rpe,
        isCompleted = set.isCompleted,
        previous = previous,
        showPrevious = showPrevious,
        onClick = onClick,
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
    previous: String? = null,
    showPrevious: Boolean = true,
    onClick: (() -> Unit)? = null,
    onCompletedChange: ((Boolean) -> Unit)? = null,
) {
    LoggedSetRow(
        setNumber = setNumber,
        setType = set.setType,
        weight = set.weight,
        reps = set.reps,
        rpe = set.rpe,
        isCompleted = set.isCompleted,
        previous = previous,
        showPrevious = showPrevious,
        onClick = onClick,
        onCompletedChange = onCompletedChange,
        modifier = modifier,
    )
}

/**
 * The column labels: `SET`, `PREV`, `KG × REPS`, `RPE`.
 *
 * Drawn as an eyebrow rather than as a table header, because there is no table — the rows
 * are a `Column` and the columns line up because both use these widths. Reading the header
 * out to a screen reader would then announce four words with nothing under them, so it is
 * hidden from semantics; each field carries its own label instead.
 *
 * @param showPrevious must match what the rows under it were given, since nothing here can
 *   check: a header that keeps a column the rows dropped is labels sitting over the wrong
 *   cells.
 */
@Composable
fun SetLogHeader(modifier: Modifier = Modifier, showPrevious: Boolean = true) {
    val weightUnit = LocalUnitSystem.current.weightSuffix

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .padding(horizontal = OpenGymTheme.spacing.md, vertical = OpenGymTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(
            text = stringResource(R.string.set_log_column_set),
            modifier = Modifier.width(INDEX_COLUMN_WIDTH),
        )
        if (showPrevious) {
            Eyebrow(
                text = stringResource(R.string.set_log_column_previous),
                modifier = Modifier.width(PREVIOUS_COLUMN_WIDTH),
            )
        }
        Eyebrow(
            text = stringResource(R.string.set_log_column_weight_reps, weightUnit),
            modifier = Modifier.weight(1f),
        )
        Eyebrow(
            text = stringResource(R.string.set_log_column_rpe),
            modifier = Modifier.width(RPE_COLUMN_WIDTH),
        )
        Box(modifier = Modifier.width(TOGGLE_SIZE))
    }
}

// ─── Cells ──────────────────────────────────────────────────────────────────────────────

/**
 * The ground a set row sits on, its padding, and the error under it.
 *
 * Internal rather than private because [SetEditor] is the same row reopened: it draws the same
 * cells on the same grid, and a frame of its own would be a second set of paddings that drift.
 * The block of controls an editor puts under the row goes in [footer], inside this `Column`, so
 * that the whole editor reads as one tinted block rather than as a row with things below it.
 */
@Composable
internal fun SetRowFrame(
    modifier: Modifier,
    isActive: Boolean,
    isCompleted: Boolean,
    error: String?,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
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
            .padding(horizontal = OpenGymTheme.spacing.md, vertical = OpenGymTheme.spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = OpenGymTheme.spacing.xs),
            )
        }
        footer?.invoke(this)
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
internal fun SetIndexCell(setNumber: Int, setType: SetType, isActive: Boolean) {
    val spoken = stringResource(R.string.set_log_position, setType.label(), setNumber)

    SetMarker(
        setNumber = setNumber,
        setType = setType,
        isActive = isActive,
        modifier = Modifier
            .width(INDEX_COLUMN_WIDTH)
            .clearAndSetSemantics { contentDescription = spoken },
    )
}

/**
 * The leading cell of a row whose type can be changed, or of one whose cannot.
 *
 * The branch is here rather than at each call site because both rows that have it — the entry row
 * and the editor — have to draw the *same* cell either way: a table whose leading column changed
 * width when a session was paused would relayout every figure in it.
 */
@Composable
internal fun SetTypeCell(
    setNumber: Int,
    setType: SetType,
    onSetTypeChange: ((SetType) -> Unit)?,
    isActive: Boolean,
    enabled: Boolean,
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
    )
}

/**
 * What was done last time, or a dash holding the column open.
 *
 * The dash is the point. A set with no history is the ordinary case in a table that has some —
 * last session was three sets, this one is five — and a cell that collapsed would shift every
 * cell to its right by the column's width, so two rows of one table would not line up.
 */
@Composable
internal fun PreviousCell(previous: String?, showPrevious: Boolean) {
    if (!showPrevious) return
    val placeholder = stringResource(R.string.set_log_previous_none)
    val spoken = previous?.let { stringResource(R.string.set_log_previous_value, it) }

    Text(
        text = previous ?: placeholder,
        style = OpenGymTheme.typography.numericSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
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
 * A weight or rep field.
 *
 * [BasicTextField] rather than `OutlinedTextField`: Material's text fields carry a floating
 * label, a 56dp minimum height and their own container, and four of them across a 300dp row
 * is not a layout. What is kept is what the field is *for* — a numeric keyboard, a single
 * line, a visible focus and error state, and a label a screen reader can read.
 *
 * [error] is carried here rather than on the row's frame because the frame is a `Column` with
 * no text and no role: TalkBack never stops on it, so an error announced there is an error
 * nobody hears. On the field it is read out with the value that was refused.
 */
@Composable
internal fun SetValueField(
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val border = if (error != null) MaterialTheme.colorScheme.error else Color.Transparent

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        readOnly = !enabled,
        singleLine = true,
        textStyle = OpenGymTheme.typography.numeric.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.semantics {
            contentDescription = description
            if (error != null) this.error(error)
        },
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .border(HAIRLINE_WIDTH, border, shape)
                    .padding(vertical = OpenGymTheme.spacing.md),
                contentAlignment = Alignment.Center,
                content = { field() },
            )
        },
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
internal fun RpeCell(setNumber: Int, rpe: Int?) {
    val unset = stringResource(R.string.set_log_rpe_unset)
    val spoken = if (rpe != null) {
        stringResource(R.string.set_log_rpe_value, setNumber, formatRpe(rpe))
    } else {
        stringResource(R.string.set_log_rpe_none, setNumber)
    }

    Text(
        text = rpe?.let(::formatRpe) ?: unset,
        style = OpenGymTheme.typography.numericSmall,
        color = if (rpe == null) {
            MaterialTheme.colorScheme.outline
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .width(RPE_COLUMN_WIDTH)
            .semantics { contentDescription = spoken },
    )
}

/**
 * The tick.
 *
 * A real toggle rather than a tinted glyph: it is the control the whole row exists to reach,
 * it has to announce its checked state, and `IconToggleButton` is what gives it a 48dp touch
 * target regardless of the 36dp it is drawn at.
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
    val spoken = if (isCompleted) {
        stringResource(R.string.set_log_mark_not_done, setNumber)
    } else {
        stringResource(R.string.set_log_mark_done, setNumber)
    }

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
            .semantics { contentDescription = spoken },
    ) {
        Icon(imageVector = Icons.Filled.Check, contentDescription = null)
    }
}

@Composable
@Suppress("LongParameterList")
private fun LoggedSetRow(
    setNumber: Int,
    setType: SetType,
    weight: Long?,
    reps: Int?,
    rpe: Int?,
    isCompleted: Boolean,
    previous: String?,
    showPrevious: Boolean,
    onClick: (() -> Unit)?,
    onCompletedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
) {
    val performed = when {
        weight != null && reps != null ->
            stringResource(R.string.set_log_weight_and_reps, formatWeight(weight), reps)

        reps != null -> stringResource(R.string.set_log_reps_only, reps)
        weight != null -> formatWeight(weight)
        else -> stringResource(R.string.set_log_rpe_unset)
    }
    val editLabel = stringResource(R.string.set_log_edit, setNumber)

    // Merged only when there is something to tap. A row that can be reopened is one target and
    // has to announce itself as one — four separate stops with a click action hanging off their
    // parent is a row TalkBack can read and cannot press. A row that cannot be reopened keeps
    // the per-cell reading it already had, which is what lets a reader hear the RPE of the
    // third set without hearing the other three cells first.
    val tap = onClick?.let { click ->
        Modifier
            .clickable(onClickLabel = editLabel, onClick = click)
            .semantics(mergeDescendants = true) { }
    } ?: Modifier

    SetRowFrame(
        modifier = modifier,
        isActive = false,
        isCompleted = isCompleted,
        error = null,
    ) {
        // The cells are the tap target, and the tick is outside it. Two actions on one row have to
        // be two nodes: with the whole row merged, the tick's own click is swallowed by the
        // parent's and a screen reader is left able to reopen the set and unable to un-tick it —
        // while a thumb aiming at the tick would open the editor instead. The grid is unaffected,
        // because the group takes the width the tick does not: see the note on the columns below.
        Row(
            modifier = Modifier.weight(1f).then(tap),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SetIndexCell(setNumber = setNumber, setType = setType, isActive = false)
            PreviousCell(previous = previous, showPrevious = showPrevious)
            Text(
                text = performed,
                style = OpenGymTheme.typography.numeric,
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
 * because a logged set reading `60 kg ×` with the reps clipped off is a row that has lost the
 * only thing it was recording. Every dp taken off a fixed column goes there. The touch targets
 * are unaffected — [TOGGLE_SIZE] is what the tick is *drawn* at, and `IconToggleButton` keeps
 * its 48dp target regardless.
 *
 * Two of the four are wider than the figures in them need, and deliberately: the leading cell and
 * the rating cell are *controls* on a row being typed — the set type's menu and the rating's — so
 * they are sized to be hit rather than to fit their contents. Eight dp between them is what that
 * costs the value columns, which is a character of a rep count nobody logs.
 *
 * A logged row groups every cell except the tick into one tap target, and the grid survives that
 * because the group takes the width the tick does not: with one gap between them, the group's own
 * cells land on exactly the columns a flat row puts them on.
 */

internal val INDEX_COLUMN_WIDTH: Dp = 28.dp
internal val PREVIOUS_COLUMN_WIDTH: Dp = 44.dp
internal val RPE_COLUMN_WIDTH: Dp = 32.dp
internal val TOGGLE_SIZE: Dp = 32.dp
internal const val WEIGHT_COLUMN_WEIGHT = 1.3f
internal const val REPS_COLUMN_WEIGHT = 1f

/** The `×` between the two fields. U+00D7, not the letter x, which is narrower and lower. */
internal const val MULTIPLICATION_SIGN = "×"

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
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SetLogTable()
    }
}

@Preview(name = "Set log - logging a set, dark", widthDp = 360)
@Composable
fun SetLogRowDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        SetLogTable()
    }
}

@Preview(name = "Set log - a refused set", widthDp = 360)
@Composable
fun SetLogRowInvalidPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        OpenGymCard(modifier = Modifier.fillMaxWidth().padding(OpenGymTheme.spacing.screen)) {
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
                previous = "75 × 6",
                isActive = true,
                error = "reps must be within 1..10,000, was 0",
            )
        }
    }
}

@Composable
private fun SetLogTable() {
    OpenGymCard(modifier = Modifier.fillMaxWidth().padding(OpenGymTheme.spacing.screen)) {
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
            previous = "60 × 10",
            onClick = {},
            onCompletedChange = {},
        )
        HairlineDivider()
        // Ticked off again: the set is still in the table, under the number it had, and no longer
        // counts towards anything — see `MarkSetCompletedUseCase`.
        LoggedSetRow(
            setNumber = 2,
            set = ActiveSet(reps = 8, weight = 70_000_000, rpe = 75, isCompleted = false),
            previous = "70 × 8",
            onClick = {},
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
            previous = "75 × 6",
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
