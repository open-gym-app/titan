package com.opengym.feature.history.template.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.OpenGymValueField
import com.opengym.core.ui.component.SupersetBadge
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.template.TemplateExerciseRow
import com.opengym.feature.history.template.TemplateField

/**
 * One exercise of a template: what it is, and what a set of it is meant to be.
 *
 * ```text
 * ┌──────────────────────────────────────────┐
 * │ 1  Bench press                 SS A1   × │
 * │   ┌────┐ ┌────┐ ┌────┐ ┌────┐            │
 * │   │ 4  │ │ 8  │ │ 80 │ │ 90 │            │
 * │   SETS   REPS    KG     REST             │
 * └──────────────────────────────────────────┘
 * ```
 *
 * ### Four figures, all optional
 *
 * A template is a plan, not a prescription: an exercise with nothing filled in still says "do
 * this, here, in this order", which is most of what a template is for. So every field may be
 * blank, and a blank one means the line declines to say — the session falls back to the user's
 * default rest and to an empty set row, exactly as a session built by hand does.
 *
 * The cells are [OpenGymValueField]s, the same box a set is logged into on the workout screen,
 * because that is what these numbers *become*. Their captions are under them rather than in a
 * header row: a card carries one line of them, and a header over four cells would be a second
 * row of text saying what four three-character boxes already imply.
 *
 * ### Why the × is a button and not a swipe
 *
 * Deleting a *set* is a swipe, because a row of a table is nearest the thumb and a destructive
 * action should be the hardest gesture on it. Here the destructive action is the only one on the
 * card that is not typing, and the card is also the thing being dragged — a horizontal swipe on
 * an item that is picked up by holding it is two gestures competing for the same finger.
 *
 * @param position where it sits in the plan, from 0. The *drawn* order, which while a drag is in
 *   flight is not the stored one.
 * @param enabled false makes the whole card read-only — a write is out. The fields keep what
 *   they hold, because that is still the user's copy of it.
 */
@Composable
internal fun TemplateExerciseCard(
    row: TemplateExerciseRow,
    position: Int,
    onFieldChange: (TemplateField, String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val name = row.exercise?.name ?: stringResource(R.string.template_exercise_removed)

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (position + 1).toString(),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.supersetLabel != null) SupersetBadge(label = row.supersetLabel)
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.template_remove_exercise, name),
                        modifier = Modifier.size(REMOVE_ICON_SIZE),
                    )
                }
            }

            DefaultsRow(row = row, onFieldChange = onFieldChange, enabled = enabled)
        }
    }
}

/**
 * The four cells, equally weighted.
 *
 * Equal because none of them is the important one: a lifter reading a plan wants the sets and
 * the reps, and one filling one in wants the weight, and giving any of them more room would be
 * this screen guessing which. Four equal thirds of a 360dp card is three characters each, which
 * is what every value here is — `12`, `80`, `90`.
 */
@Composable
private fun DefaultsRow(
    row: TemplateExerciseRow,
    onFieldChange: (TemplateField, String) -> Unit,
    enabled: Boolean,
) {
    val name = row.exercise?.name ?: stringResource(R.string.template_exercise_removed)
    val invalid = stringResource(R.string.template_field_invalid)
    val unit = LocalUnitSystem.current.weightSuffix

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        DefaultField(
            value = row.sets,
            field = TemplateField.SETS,
            caption = stringResource(R.string.template_field_sets),
            description = stringResource(R.string.template_field_sets_description, name),
            row = row,
            invalid = invalid,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        DefaultField(
            value = row.reps,
            field = TemplateField.REPS,
            caption = stringResource(R.string.template_field_reps),
            description = stringResource(R.string.template_field_reps_description, name),
            row = row,
            invalid = invalid,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        DefaultField(
            value = row.weight,
            field = TemplateField.WEIGHT,
            // The ambient unit, so both the caption and the spoken description say which number
            // the user is typing. Read here rather than passed in: a change to the setting
            // repaints it without the screen being revisited.
            caption = unit,
            description = stringResource(R.string.template_field_weight_description, name, unit),
            row = row,
            invalid = invalid,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
        DefaultField(
            value = row.rest,
            field = TemplateField.REST,
            caption = stringResource(R.string.template_field_rest),
            description = stringResource(R.string.template_field_rest_description, name),
            row = row,
            invalid = invalid,
            enabled = enabled,
            onFieldChange = onFieldChange,
        )
    }
}

/** One cell of [DefaultsRow], wired to the row's error set. */
@Composable
private fun RowScope.DefaultField(
    value: String,
    field: TemplateField,
    caption: String,
    description: String,
    row: TemplateExerciseRow,
    invalid: String,
    enabled: Boolean,
    onFieldChange: (TemplateField, String) -> Unit,
) {
    OpenGymValueField(
        value = value,
        onValueChange = { onFieldChange(field, it) },
        description = description,
        // Decimal for the weight, which is legitimately `72.5`, and whole numbers for the three
        // that count things. The keyboard is a hint rather than a constraint — a hardware
        // keyboard can type anything — which is why the parse still guards all four.
        keyboardType = if (field == TemplateField.WEIGHT) {
            KeyboardType.Decimal
        } else {
            KeyboardType.Number
        },
        enabled = enabled,
        error = invalid.takeIf { field in row.errors },
        modifier = Modifier.weight(1f),
        caption = caption,
    )
}

/** Matches the icon size of the workout card's own slot controls. */
private val REMOVE_ICON_SIZE: Dp = 18.dp
