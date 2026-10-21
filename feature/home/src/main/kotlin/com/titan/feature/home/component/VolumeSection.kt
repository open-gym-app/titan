package com.titan.feature.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.model.WeeklyVolume
import com.titan.core.ui.chart.ChartEntry
import com.titan.core.ui.chart.ChartPlot
import com.titan.core.ui.chart.ChartScale
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatMonthAbbreviation
import com.titan.core.ui.format.formatVolume
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.home.R

/**
 * What the last eight weeks lifted, one bar per week.
 *
 * ```text
 * VOLUME · LAST 8 WEEKS ────────────────────
 * ┌──────────────────────────────────────────┐
 * │              ▃     ▅  █                  │
 * │  ▂  ▃  ▂  ▄  ▃  ▄  ▅  █                  │
 * │  MAR              APR                    │
 * └──────────────────────────────────────────┘
 * ```
 *
 * ### Bars, and the heaviest week in the accent
 *
 * `:core:ui`'s [ChartPlot] on the bar scale — the same component the stats hub and the exercise
 * screen draw with, and the reason a chart is never written inside a feature: the peak
 * treatment, the zero-week baseline, the flat-series case, the month labels and the sentence a
 * screen reader is given are one rule there and four inconsistent ones here. Bars rather than a
 * line because the question a lifter asks of eight weeks is which of them was the big one.
 *
 * ### The figures are formatted here, not in the ViewModel
 *
 * [WeeklyVolume] carries milligram-reps and Mondays; [formatVolume] follows the ambient unit
 * system, so switching to pounds repaints the card without the ViewModel being told. Same split
 * `VolumeHeroCard` makes on the stats hub, and the reason [ChartEntry] takes a value *and* the
 * string it reads as.
 *
 * ### No tooltip, because the card is a way in
 *
 * The hub's charts open a bubble on the mark a thumb lands on; this one navigates. Two things
 * cannot own the same tap, and on a dashboard the more useful of the two is the screen where
 * every figure is already written out — so no `onSelect` is passed and the plot installs no tap
 * detector at all. No value rail either: a glance is a shape, and the figures are one tap away.
 *
 * @param weeks one bucket per week, ascending, untrained weeks included as zeroes. Never
 *   empty — a period with no training draws no card at all, which is the caller's check because
 *   the card includes a heading.
 * @param onClick the card was tapped: the user wants the stats the chart is a glance at.
 */
@Composable
fun VolumeSection(
    weeks: List<WeeklyVolume>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.home_volume_label)
    val entries = weeks.map { week ->
        ChartEntry(
            // Milligram-reps run to eleven digits and a `Float` holds seven of them, which is a
            // relative error a thousand times finer than a pixel on this chart. The exact
            // figures travel as strings, where they are read rather than plotted.
            value = week.totalVolume.toFloat(),
            label = formatMonthAbbreviation(week.weekStart),
            formattedValue = formatVolume(week.totalVolume),
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        SectionHeading(
            text = pluralStringResource(R.plurals.home_volume_heading, weeks.size, weeks.size),
        )

        TitanCard {
            ChartPlot(
                entries = entries,
                label = label,
                scale = ChartScale.BAR,
                // Inside the card and before the padding, as the other two cards' clicks are.
                modifier = Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.home_volume_open),
                        onClick = onClick,
                    )
                    .padding(TitanTheme.spacing.card),
            )
        }
    }
}
