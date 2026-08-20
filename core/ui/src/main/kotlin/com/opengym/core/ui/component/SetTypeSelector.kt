package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What kind of set this is: a working set, a warm-up, a drop set, one taken to failure, or an
 * interval.
 *
 * A row of chips over the whole of [SetType], because it is a closed vocabulary of five and the
 * user picks exactly one. Labelled from `DomainLabels`, so the words a chip shows and the words
 * a screen reader says of the same set in a logged row are the same words.
 *
 * The scrolling row rather than a wrapped grid: five labels do not fit across a 360dp screen,
 * and the alternative — two rows of chips inside a card that already holds a table — costs more
 * vertical space than the control is worth in the middle of a workout.
 *
 * @param selected the type currently set, which is [SetType.NORMAL] for a set nobody has
 *   marked. There is no null: every set has a type, and "not marked" *is* the working set.
 * @param onSelect the type tapped. Emitted for the already-selected chip too, which the caller
 *   can treat as a no-op — a radio group whose selected member is inert is one a user pokes at.
 * @param contentPadding the row's inset. Defaults to the screen margin because the row is
 *   normally drawn edge to edge so a chip can scroll out from under it; a caller placing it
 *   inside a card that already has padding passes zero.
 * @param enabled false makes the row read-only, which is what a paused or finished session
 *   needs: the set's type is still worth reading, and the type of a set that cannot be written
 *   is not worth changing. It is the same read-only the entry row above it goes into, so the
 *   whole card answers the pause the same way.
 */
@Composable
fun SetTypeSelector(
    selected: SetType,
    onSelect: (SetType) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 0.dp),
    enabled: Boolean = true,
) {
    OpenGymFilterChipRow(modifier = modifier, contentPadding = contentPadding) {
        items(SetType.entries, key = SetType::name) { type ->
            OpenGymFilterChip(
                label = type.label(),
                selected = type == selected,
                onClick = { onSelect(type) },
                enabled = enabled,
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Set type selector", widthDp = 360)
@Composable
fun SetTypeSelectorPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SetTypeSelectorCatalogue()
    }
}

@Preview(name = "Set type selector, dark", widthDp = 360)
@Composable
fun SetTypeSelectorDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        SetTypeSelectorCatalogue()
    }
}

@Composable
private fun SetTypeSelectorCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        SetTypeSelector(
            selected = SetType.NORMAL,
            onSelect = {},
            contentPadding = PaddingValues(horizontal = OpenGymTheme.spacing.screen),
        )
        SetTypeSelector(
            selected = SetType.WARMUP,
            onSelect = {},
            contentPadding = PaddingValues(horizontal = OpenGymTheme.spacing.screen),
        )
    }
}
