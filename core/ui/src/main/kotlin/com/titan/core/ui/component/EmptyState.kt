package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.TitanTheme

/**
 * What a list says when it has nothing in it: a glyph, a heading, a sentence, and — if there
 * is one — the thing to do about it.
 *
 * Two situations wear this, and they are not the same one:
 *
 * - **Nothing yet.** No sessions logged, no templates written. There is an action, because
 *   the screen exists to be filled and the user has not filled it.
 * - **Nothing matching.** A search or a filter excluded everything. There is no action
 *   beyond undoing what the user just did, so [action] is usually the way back to the
 *   unfiltered list.
 *
 * They are one component because they are one *layout*, and a screen that hand-rolled the
 * second would centre it slightly differently from the first.
 *
 * @param glyph a single character, drawn large and quiet. A character rather than an icon
 *   because the design's whole vocabulary of marks is typographic — see the wireframes' `G`
 *   glyphs — and because an illustration at this size is the one thing that would make a
 *   dense instrument panel look like a toy. Hidden from screen readers: [title] says it.
 * @param action the primary way out, or null. An empty state with a button that only
 *   restates the heading is worse than one with no button.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    glyph: String? = null,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TitanTheme.spacing.screen, vertical = VERTICAL_INSET),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                style = TitanTheme.typography.numericLarge,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null && actionLabel != null) {
            OutlinedButton(
                onClick = action,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = TitanTheme.spacing.md),
            ) {
                Eyebrow(text = actionLabel, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Enough air above and below that the state reads as a considered page rather than as a
 * paragraph that failed to load. Not a scale step: it is one measurement, used once.
 */
private val VERTICAL_INSET = 48.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Empty state", widthDp = 360)
@Composable
fun EmptyStatePreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        EmptyStateCatalogue()
    }
}

@Preview(name = "Empty state, dark", widthDp = 360)
@Composable
fun EmptyStateDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        EmptyStateCatalogue()
    }
}

@Composable
private fun EmptyStateCatalogue() {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        EmptyState(
            glyph = "⌕",
            title = "No exercises match",
            description = "Nothing in the library matches “sumo” with those filters.",
            actionLabel = "Clear filters",
            action = {},
        )
        HairlineDivider()
        EmptyState(
            glyph = "◇",
            title = "Nothing logged yet",
            description = "Finished sessions show up here.",
        )
    }
}
