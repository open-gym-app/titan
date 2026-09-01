package com.opengym.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.theme.OpenGymTheme

/*
 * Every chart primitive, in one page, in both schemes.
 *
 * It lives in `src/test` for the reason `ThemeCatalog` does: it is documentation of the design
 * system rather than a component anything is meant to use, so it must not ship in the library.
 * It is still an ordinary pair of `@Preview`s — open this file to see what a chart looks like
 * rather than guessing, and the Roborazzi plugin captures both as references, which turns an
 * accidental change to the drawing into a reviewable image diff.
 *
 * The per-component previews beside each chart cover its *edge* cases — flat, single-point,
 * empty. This covers the ordinary one, assembled the way a screen assembles it: a card, an
 * eyebrow, a hero figure, a change chip, the plot and its axis.
 */

@Preview(name = "Catalog - charts - light", widthDp = 360, heightDp = 620)
@Composable
fun ChartCatalogLightPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) { ChartCatalog() }
}

@Preview(name = "Catalog - charts - dark", widthDp = 360, heightDp = 620)
@Composable
fun ChartCatalogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) { ChartCatalog() }
}

@Composable
private fun ChartCatalog() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        SectionHeading(text = "Line")
        ChartCard(label = "Cumulative volume", value = "184,320", unit = "kg") {
            ChartPlot(
                entries = previewRising(),
                label = "Cumulative volume",
                scale = ChartScale.LINE,
                formatValue = ::previewFormat,
            )
        }

        SectionHeading(text = "Bars")
        ChartCard(label = "Volume per session", value = "16,800", unit = "kg") {
            ChartPlot(
                entries = previewPeaked(),
                label = "Volume per session",
                scale = ChartScale.BAR,
                formatValue = ::previewFormat,
            )
        }

        SectionHeading(text = "Ring")
        Row(
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(progress = RING_PROGRESS, label = "Level 14", valueLabel = "2,140 of 3,000 XP") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow(text = "Lvl")
                    Text(
                        text = "14",
                        style = OpenGymTheme.typography.numericLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
                Eyebrow(text = "XP · 2,140 / 3,000")
                Text(
                    text = "Iron Disciple",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ChangeChip(direction = ChangeDirection.UP, value = "18%")
            }
        }
    }
}

/** The shape every caller wraps a chart in: an eyebrow, a hero figure, a change chip, the plot. */
@Composable
private fun ChartCard(
    label: String,
    value: String,
    unit: String,
    content: @Composable () -> Unit,
) {
    OpenGymCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            Eyebrow(text = label)
            Row(
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = value,
                    style = OpenGymTheme.typography.numericLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = unit,
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ChangeChip(direction = ChangeDirection.UP, value = "18%")
            }
            content()
        }
    }
}

private const val RING_PROGRESS = 0.71f
