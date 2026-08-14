package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The facts about a thing, two to a row inside one card.
 *
 * The wireframes' metadata block — what an exercise is done with, how hard it is, what a set
 * of it records. It is the *quiet* counterpart of [SessionStatGrid]: that one is four figures
 * in four bordered tiles because the numbers are the point, and this one is small labels over
 * ordinary prose inside a single card because the answers are context for the screen, not its
 * subject. Drawing these as stat tiles would give an exercise's equipment the same visual
 * weight as a session's total volume.
 *
 * Two columns because a 360dp screen minus its margins and the card's inset leaves about
 * 150dp a side, which fits every label in the vocabulary without wrapping.
 *
 * Callers build [entries] with `listOfNotNull`, dropping the facts they have no value for —
 * the seed leaves the mechanic unset on stretches, and a user-authored exercise has almost
 * none of them. An odd count leaves the last row half empty rather than stretching the cell
 * across it, so the columns still line up when read against the row above. An empty list
 * draws nothing at all, so a caller need not check.
 *
 * Each cell announces as one node: a label and a value read as two unrelated fragments are
 * "Equipment", then "Barbell" a swipe apart, which is not the same information.
 */
@Composable
fun MetaGrid(entries: List<MetaEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            entries.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
                    row.forEach { entry ->
                        MetaCell(entry = entry, modifier = Modifier.weight(1f))
                    }
                    // The gap a half-full last row leaves. Without it the single cell takes
                    // the whole width and its value no longer starts under the one above it.
                    repeat(COLUMNS - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaCell(entry: MetaEntry, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.stat_description, entry.label, entry.value)

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Eyebrow(text = entry.label)
        Text(
            text = entry.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two, for the reason [MetaGrid] gives. Not a parameter: a third column does not fit. */
private const val COLUMNS = 2

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private val PREVIEW_ENTRIES = listOf(
    MetaEntry(label = "Type", value = "Powerlifting"),
    MetaEntry(label = "Equipment", value = "Barbell"),
    MetaEntry(label = "Mechanic", value = "Compound"),
    MetaEntry(label = "Difficulty", value = "Intermediate"),
    MetaEntry(label = "Tracked", value = "Weight, reps, RPE"),
)

@Preview(name = "Meta grid", widthDp = 360)
@Composable
fun MetaGridPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MetaGridCatalogue()
    }
}

@Preview(name = "Meta grid, dark", widthDp = 360)
@Composable
fun MetaGridDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        MetaGridCatalogue()
    }
}

@Composable
private fun MetaGridCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        // Five entries: the odd one out sits alone, under the cell it lines up with.
        MetaGrid(entries = PREVIEW_ENTRIES)
        // What a user-authored exercise knows about itself, which is not much.
        MetaGrid(entries = PREVIEW_ENTRIES.filter { it.label == "Tracked" })
    }
}
