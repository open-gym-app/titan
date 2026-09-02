package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * A record as a row: what kind it is on top, when it was set under it, and what it reads in the
 * accent on the end.
 *
 * The list form of [PrValue], which is the same record as a figure on the end of somebody else's
 * row. Two screens draw a record as a row of its own — the exercise's own bests, and the records
 * list across every exercise — and they are the same three pieces in the same three type roles.
 * A second copy of that is a copy that drifts: the date creeps to `bodyMedium` on one screen, the
 * figure loses the accent on the other, and a user who has seen both is looking at two designs.
 *
 * ### Strings, not a [com.opengym.core.domain.model.PersonalRecord]
 *
 * The two callers hold different models — a bare record on one screen, a record joined to its
 * exercise and to what it beat on the other — and there is no type here that both of them have.
 * Formatting is the caller's, through `formattedValue` and `formatDayAndMonth`, so this stays a
 * layout and every screen keeps the units the user chose.
 *
 * ### The whole row is one node to a screen reader
 *
 * Semantics are cleared and replaced with [contentDescription], because a reader moving through
 * two hundred records should hear one sentence per row rather than five fragments to assemble.
 * That takes every child's text with it — the [badge] and [trailing] slots included — so a
 * caller's sentence has to say what its slots draw. See `PrValue`, which makes the same call.
 *
 * @param kind what the record is of, as an eyebrow — `Heaviest`, `Most reps`. From `PrType.label`.
 * @param date when it was set, in the quiet line under the kind.
 * @param contentDescription the whole row as one sentence. See above.
 * @param value the record's own figure — `92 kg` — in the accent. **Null draws nothing**, which
 *   is the honest answer for a record whose type and populated column disagree; see
 *   `formattedValue`, whose null this is meant to be passed straight through.
 * @param name the exercise the record is on, between the kind and the date. For the list that
 *   crosses exercises; null on a screen that is already about one, where repeating it down the
 *   rows says nothing.
 * @param badge beside the kind, for what marks a row out from its neighbours — the records list
 *   flags the ones set in the last session there.
 * @param trailing under the figure, for what qualifies it — the improvement over the record it
 *   beat.
 */
@Composable
fun PersonalRecordRow(
    kind: String,
    date: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    name: String? = null,
    badge: @Composable RowScope.() -> Unit = {},
    trailing: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow(text = kind)
                badge()
            }
            if (name != null) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = date,
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The figure in the accent, as every record in this app is drawn — see `PrValue`, whose
        // treatment this matches so a best in a list and the same best on a library row read
        // alike. The padding and the click, if there is one, are the caller's: a row inside a
        // card and a row in a full-bleed list are inset differently, and only the caller knows
        // which it is.
        Column(horizontalAlignment = Alignment.End) {
            if (value != null) {
                Text(
                    text = value,
                    style = OpenGymTheme.typography.numeric,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            trailing()
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Personal record row", widthDp = 360)
@Composable
fun PersonalRecordRowPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PersonalRecordRowCatalogue()
    }
}

@Preview(name = "Personal record row, dark", widthDp = 360)
@Composable
fun PersonalRecordRowDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        PersonalRecordRowCatalogue()
    }
}

/** The row bare, as a card draws it, and with everything the list hangs off it. */
@Composable
private fun PersonalRecordRowCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        PersonalRecordRow(
            kind = "Heaviest",
            date = "21 Apr",
            contentDescription = "Heaviest, 92 kg, 21 April",
            value = "92 kg",
        )
        PersonalRecordRow(
            kind = "Heaviest",
            date = "21 Apr",
            contentDescription = "Bench press, heaviest, 92 kg, 21 April, new, up 2 kg",
            value = "92 kg",
            name = "Bench press",
            badge = {
                Eyebrow(text = "New", color = MaterialTheme.colorScheme.primary)
            },
            trailing = {
                Text(
                    text = "+2 kg",
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            },
        )
    }
}
