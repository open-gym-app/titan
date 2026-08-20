package com.opengym.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.core.ui.theme.PillShape

/**
 * A filter, as a capsule that is either on or off.
 *
 * The interactive twin of [OpenGymBadge], which is a *tag* — same shape, same type role, no
 * click. Keeping them as two components rather than one with an optional `onClick` is what
 * stops a static label acquiring a 32dp touch target and a role that tells a screen reader it
 * can be pressed.
 *
 * Not Material's `FilterChip`, for the reason [OpenGymCard] is not Material's `Card`:
 * `FilterChip` is a 32dp container with a leading-icon slot, its own elevation and eight
 * colour slots, and reaching this look through `FilterChipDefaults` means every call site
 * restating the same six arguments. What is left once those are settled is a bordered pill
 * with two states, which is the whole of this file.
 *
 * **[Role.RadioButton], not `Checkbox`.** A chip row is a set of alternatives where one is
 * chosen — including the "all" chip that clears the filter — and that is what a screen reader
 * should say. A row where several can be on at once passes [multiple] and gets the checkbox
 * role instead; see [OpenGymFilterChipRow], which does not make that choice for you.
 *
 * @param label the text. Uppercased for you in the composition's locale, as [Eyebrow] does
 *   and for the same reason: the case is typography, not content, and a translation file
 *   should not have to carry it.
 * @param multiple whether this chip is one of several that can be on at once. Changes only
 *   what the chip announces itself as.
 * @param enabled false makes the chip inert without changing what it shows, the way
 *   [SetLogRow]'s fields go read-only: the row is still saying which option is chosen, and a
 *   greyed-out answer is harder to read than a live one. What goes is the tap and the
 *   click action a screen reader would offer.
 */
@Composable
fun OpenGymFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    multiple: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = if (multiple) Role.Checkbox else Role.RadioButton,
            onClick = onClick,
        ),
        shape = PillShape,
        // The selected chip is the accent's ordinary, un-shouted use: it marks the one thing
        // on this row that is currently doing something. An unselected chip is the page's own
        // surface with a hairline round it, so a row of eight reads as one texture and the
        // chosen one is the only thing that stands out of it.
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(
            width = HAIRLINE_WIDTH,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Text(
            text = label.uppercase(Locale.current.platformLocale),
            style = OpenGymTheme.typography.eyebrow,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = OpenGymTheme.spacing.lg,
                vertical = OpenGymTheme.spacing.md,
            ),
        )
    }
}

/**
 * A scrolling row of them, bleeding to the screen edge.
 *
 * A [LazyRow] rather than a `Row` with `horizontalScroll`, because the muscle filter is
 * seventeen chips wide and the ones off-screen should not be measured to draw the two that
 * are. The screen margin is [PaddingValues] on the row's *content* rather than on the row, so
 * the first chip starts on the margin and the last one scrolls past it instead of stopping
 * short of the edge — which is what tells the user the row moves.
 */
@Composable
fun OpenGymFilterChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = OpenGymTheme.spacing.screen),
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
        content = content,
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Filter chips", widthDp = 360)
@Composable
fun OpenGymFilterChipPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        FilterChipCatalogue()
    }
}

@Preview(name = "Filter chips, dark", widthDp = 360)
@Composable
fun OpenGymFilterChipDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        FilterChipCatalogue()
    }
}

@Composable
private fun FilterChipCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        OpenGymFilterChipRow {
            items(listOf("All", "Chest", "Back", "Quads", "Shoulders", "Biceps")) { label ->
                OpenGymFilterChip(label = label, selected = label == "All", onClick = {})
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            modifier = Modifier.padding(horizontal = OpenGymTheme.spacing.screen),
        ) {
            OpenGymFilterChip(label = "Barbell", selected = true, onClick = {}, multiple = true)
            OpenGymFilterChip(label = "Dumbbell", selected = false, onClick = {}, multiple = true)
            OpenGymFilterChip(label = "Cable", selected = false, onClick = {}, multiple = true)
        }
    }
}
