package com.opengym.feature.progression.stats.component

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
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.chart.ChangeChip
import com.opengym.core.ui.chart.ChangeDirection
import com.opengym.core.ui.chart.ChartAxisLabels
import com.opengym.core.ui.chart.ChartEntry
import com.opengym.core.ui.chart.LineSeriesChart
import com.opengym.core.ui.chart.chartAxisLabels
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.format.formatMonthAbbreviation
import com.opengym.core.ui.format.formatPercentChange
import com.opengym.core.ui.format.formatVolume
import com.opengym.core.ui.format.formatVolumeValue
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.stats.VolumeState

/**
 * The hero: everything the period lifted, where it went week by week, and how that compares.
 *
 * ### The figure is the period, the line is its weeks
 *
 * `CUMULATIVE VOLUME` is the total — the sum of every completed set in the window — and the
 * line under it is one mark per week of that window. They are the same data at two
 * resolutions, which is what lets the card answer both "how much" and "which way" without a
 * second card: a lifter reads the number, then reads whether the weeks behind it were climbing.
 *
 * ### The formatting happens here
 *
 * [VolumeState] carries milligram-reps and Mondays, and this turns them into what the user
 * reads — through [formatVolume], which follows the ambient unit system, so switching to pounds
 * repaints the card without the ViewModel being told. That is the split CONTRIBUTING.md § Using
 * the design system asks for, and it is why the chart takes formatted strings beside its values.
 *
 * ### The chip is the only accent above the fold
 *
 * A rise takes it, a fall and a flat period do not — see [ChangeChip], which explains why a
 * lighter month is not painted in the error role.
 */
@Composable
fun VolumeHeroCard(
    state: VolumeState,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stats_volume_label)
    val entries = state.weeks.map { week ->
        ChartEntry(
            // Milligram-reps run to eleven digits and a `Float` holds seven of them, which is a
            // relative error a thousand times finer than a pixel on this chart. The exact
            // figures travel as strings in `formattedValue`, where they are read rather than
            // plotted.
            value = week.totalVolume.toFloat(),
            label = formatMonthAbbreviation(week.weekStart),
            formattedValue = formatVolume(week.totalVolume),
        )
    }

    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            Eyebrow(text = label)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = formatVolumeValue(state.total),
                    style = OpenGymTheme.typography.numericLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = LocalUnitSystem.current.weightSuffix,
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                state.changeRatio?.let { ratio ->
                    ChangeChip(
                        direction = ChangeDirection.of(ratio),
                        value = formatPercentChange(ratio),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs)) {
                LineSeriesChart(entries = entries, label = label)
                ChartAxisLabels(labels = chartAxisLabels(entries))
            }
        }
    }
}
