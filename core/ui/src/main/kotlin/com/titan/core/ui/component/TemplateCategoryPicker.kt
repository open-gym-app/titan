package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.theme.TitanTheme

/**
 * What a template trains, as one choice out of six.
 *
 * ```text
 * CATEGORY
 * [ None ] [ Strength ] [ Hypertrophy ]
 * [ Endurance ] [ Mobility ] [ General ]
 * ```
 *
 * ### "None" is a chip, not the absence of one
 *
 * [TemplateCategory] is nullable and null is deliberately *not* [TemplateCategory.GENERAL] — a
 * template saved without being asked has no category, and "General" is an answer the user gave.
 * So the null has to be selectable: without it, categorising a template by mistake would be
 * permanent, and a row would wear a chip its owner never chose. It sits first because it is the
 * state every template starts in.
 *
 * ### Where it is used
 *
 * Both places a template is named: [TemplateNameDialog], where a workout is saved as one, and
 * the template editor, where the answer is changed later. One component because the vocabulary
 * is closed and small enough to show whole — see [ChipSection] for why it wraps rather than
 * scrolls — and because two copies is how a category offered in one place goes missing in the
 * other.
 *
 * @param selected the current category, or null for a template that has none.
 * @param onSelect the new category, or null for the `None` chip. Total: the picker never refuses,
 *   because every value it can produce is a value the column accepts.
 * @param label the eyebrow over the chips. Defaulted to `Category`, and worth overriding only
 *   where the surrounding screen has already said the word.
 */
@Composable
fun TemplateCategoryPicker(
    selected: TemplateCategory?,
    onSelect: (TemplateCategory?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.template_category_label),
) {
    ChipSection(label = label, modifier = modifier) {
        TitanFilterChip(
            label = stringResource(R.string.template_category_none),
            selected = selected == null,
            onClick = { onSelect(null) },
            enabled = enabled,
        )
        TemplateCategory.entries.forEach { category ->
            TitanFilterChip(
                label = category.label(),
                selected = selected == category,
                onClick = { onSelect(category) },
                enabled = enabled,
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Template category picker", widthDp = 360)
@Composable
fun TemplateCategoryPickerPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        TemplateCategoryPickerCatalogue()
    }
}

@Preview(name = "Template category picker, dark", widthDp = 360)
@Composable
fun TemplateCategoryPickerDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        TemplateCategoryPickerCatalogue()
    }
}

@Composable
private fun TemplateCategoryPickerCatalogue() {
    // Interactive rather than two frozen states: what this component *is* is which chip moves
    // when another is tapped, and a screenshot of one selection cannot show that.
    var selected by remember { mutableStateOf<TemplateCategory?>(TemplateCategory.STRENGTH) }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        TemplateCategoryPicker(selected = selected, onSelect = { selected = it })
        TemplateCategoryPicker(selected = null, onSelect = {}, enabled = false)
    }
}
