package com.titan.feature.session.summary.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.validation.SessionEffortValidator
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R

/**
 * How the workout went, as a whole: a 1–5 star rating and the calories it burned.
 *
 * ### Two figures, two ways of entering them
 *
 * The rating saves on the tap, the way the set row's RPE menu does: a star *is* the answer, and a
 * save button under five stars would be a second question about a choice already made. Tapping the
 * star that is already the rating clears it, which is the only way back to "not rated" and costs
 * no extra control.
 *
 * Calories are a number somebody types, so they get the note's shape instead — a read row with a
 * pencil, then a field with Cancel and Save — and a half-typed figure is never written.
 *
 * ### Quiet until used
 *
 * A workout nobody rated reads as five outline stars and "Not recorded", which is one row each and
 * the same height as a rated one: the card does not grow or reflow when a figure arrives.
 *
 * @param caloriesDraft the text being typed, or null when the calories row is not being edited.
 */
@Composable
fun EffortCard(
    rating: Int?,
    calories: Int?,
    caloriesDraft: String?,
    isSaving: Boolean,
    onRate: (Int) -> Unit,
    onEditCalories: () -> Unit,
    onCaloriesDraftChange: (String) -> Unit,
    onSaveCalories: () -> Unit,
    onCancelCalories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth()) {
        RatingRow(rating = rating, enabled = !isSaving, onRate = onRate)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (caloriesDraft == null) {
            CaloriesReadRow(calories = calories, onEdit = onEditCalories)
        } else {
            CaloriesEditor(
                draft = caloriesDraft,
                isSaving = isSaving,
                onDraftChange = onCaloriesDraftChange,
                onSave = onSaveCalories,
                onCancel = onCancelCalories,
            )
        }
    }
}

/** The calories a draft would store: null for an empty field, the figure, or a refusal. */
sealed interface CaloriesInput {
    data object Empty : CaloriesInput

    data class Figure(val kilocalories: Int) : CaloriesInput

    data object Invalid : CaloriesInput

    companion object {

        /**
         * Reads [text] as whole kilocalories. Blank clears the figure; anything that is not a whole
         * number inside [SessionEffortValidator]'s bounds is refused, so the save button can be
         * dead rather than the save silently failing.
         */
        fun of(text: String): CaloriesInput {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return Empty
            val value = trimmed.toIntOrNull() ?: return Invalid
            return if (value in SessionEffortValidator.CALORIES_MIN..SessionEffortValidator.CALORIES_MAX) {
                Figure(
                    value,
                )
            } else {
                Invalid
            }
        }
    }
}

@Composable
private fun RatingRow(
    rating: Int?,
    enabled: Boolean,
    onRate: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TitanTheme.spacing.card, vertical = TitanTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.summary_rating),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        for (star in SessionEffortValidator.RATING_MIN..SessionEffortValidator.RATING_MAX) {
            val lit = rating != null && star <= rating
            IconToggleButton(
                checked = star == rating,
                onCheckedChange = { onRate(star) },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = if (lit) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(
                        R.string.summary_rating_star,
                        star,
                        SessionEffortValidator.RATING_MAX,
                    ),
                    tint = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The figure as stored, with a pencil; the whole row is the target, as on the notes card. */
@Composable
private fun CaloriesReadRow(
    calories: Int?,
    onEdit: () -> Unit,
) {
    val value = calories?.let { stringResource(R.string.summary_calories_value, formatCount(it)) }
        ?: stringResource(R.string.summary_calories_empty)
    val label = stringResource(R.string.summary_calories)
    val spoken = stringResource(R.string.summary_calories_description, label, value)
    val action = stringResource(R.string.summary_calories_edit)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = action, onClick = onEdit)
            .padding(TitanTheme.spacing.card),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = TitanTheme.typography.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = action,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CaloriesEditor(
    draft: String,
    isSaving: Boolean,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val input = CaloriesInput.of(draft)

    Column(
        modifier = Modifier.padding(TitanTheme.spacing.card),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            label = { Text(stringResource(R.string.summary_calories_label)) },
            isError = input == CaloriesInput.Invalid,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        ) {
            TextButton(onClick = onCancel, enabled = !isSaving, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.summary_effort_cancel), style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onSave,
                // Enabled on an empty field, which clears the figure, for the note's reason.
                enabled = !isSaving && input != CaloriesInput.Invalid,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.summary_effort_save), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Effort card", widthDp = 360, heightDp = 560)
@Composable
fun EffortCardPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        EffortCatalogue()
    }
}

@Preview(name = "Effort card, dark", widthDp = 360, heightDp = 560)
@Composable
fun EffortCardDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        EffortCatalogue()
    }
}

@Composable
private fun EffortCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        PreviewEffortCard(rating = 4, calories = 520, draft = null)
        PreviewEffortCard(rating = null, calories = null, draft = null)
        PreviewEffortCard(rating = 2, calories = 520, draft = "61O")
    }
}

@Composable
private fun PreviewEffortCard(
    rating: Int?,
    calories: Int?,
    draft: String?,
) {
    EffortCard(
        rating = rating,
        calories = calories,
        caloriesDraft = draft,
        isSaving = false,
        onRate = {},
        onEditCalories = {},
        onCaloriesDraftChange = {},
        onSaveCalories = {},
        onCancelCalories = {},
    )
}
