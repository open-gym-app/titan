package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.TitanTheme

/**
 * An [Eyebrow] with a rule running out to the margin: `MUSCLES WORKED ─────`.
 *
 * The rule is what makes a heading in a flat, shadowless layout read as a heading. Without
 * it the eyebrow is a short line of small text above another line of text, and at a glance
 * between sets that is not a section break — it is the first line of the paragraph.
 *
 * It is also the *only* section break this design has. Cards carry a border and rows carry a
 * divider, so raising a heading by making it bigger or bolder would put it in competition
 * with the content under it; a 10sp tracked line and a hairline do the job without spending
 * any weight on it.
 *
 * Marked as a heading for accessibility, which is what lets TalkBack's heading navigation
 * jump between the sections of a long screen instead of walking every node in them.
 *
 * @param text sentence case; [Eyebrow] uppercases it.
 * @param color the eyebrow's colour. `onSurfaceVariant` by default — the accent is worth
 *   spending on the one section of a screen that matters right now, and no more than that.
 * @param trailing drawn at the end of the row, after the rule: a count, an action, a badge.
 *   The rule takes whatever width is left, so a heading with a trailing slot still runs the
 *   full measure.
 */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Eyebrow(text = text, color = color, modifier = Modifier.semantics { heading() })
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        if (trailing != null) trailing()
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Section heading", widthDp = 360)
@Composable
fun SectionHeadingPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        SectionHeadingCatalogue()
    }
}

@Preview(name = "Section heading, dark", widthDp = 360)
@Composable
fun SectionHeadingDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        SectionHeadingCatalogue()
    }
}

@Composable
private fun SectionHeadingCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        SectionHeading(text = "Muscles worked")
        SectionHeading(text = "Chest · 12")
        SectionHeading(text = "Resume", color = MaterialTheme.colorScheme.primary)
        SectionHeading(text = "History", trailing = { StatChip(value = "24", label = "sessions") })
    }
}
