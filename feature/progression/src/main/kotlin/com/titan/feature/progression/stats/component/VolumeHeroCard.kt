package com.titan.feature.progression.stats.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.ui.chart.ChangeDirection
import com.titan.core.ui.chart.ChartChange
import com.titan.core.ui.chart.ChartEntry
import com.titan.core.ui.chart.SeriesHeroCard
import com.titan.core.ui.format.formatMonthAbbreviation
import com.titan.core.ui.format.formatPercentChange
import com.titan.core.ui.format.formatVolume
import com.titan.core.ui.format.formatVolumeValue
import com.titan.core.ui.format.weightSuffix
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.feature.progression.R
import com.titan.feature.progression.stats.VolumeState

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
 * ### The arrangement is `:core:ui`'s; the figures are this module's
 *
 * [SeriesHeroCard] owns the layout, the accent rule and the tooltip, because #M5-15's body
 * weight card is the same card with a different quantity in it — see #144. What is left here
 * is the part only the stats hub can do: [VolumeState] carries milligram-reps and Mondays, and
 * this turns them into what the user reads, through [formatVolume], which follows the ambient
 * unit system. Switching to pounds therefore repaints the card without the ViewModel being
 * told, which is the split CONTRIBUTING.md § Using the design system asks for, and it is why
 * the chart takes formatted strings beside its values.
 */
@Composable
fun VolumeHeroCard(
    state: VolumeState,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stats_volume_label)

    SeriesHeroCard(
        label = label,
        value = formatVolumeValue(state.total),
        unit = LocalUnitSystem.current.weightSuffix,
        entries = state.weeks.map { week ->
            ChartEntry(
                // Milligram-reps run to eleven digits and a `Float` holds seven of them, which
                // is a relative error a thousand times finer than a pixel on this chart. The
                // exact figures travel as strings in `formattedValue`, where they are read
                // rather than plotted.
                value = week.totalVolume.toFloat(),
                label = formatMonthAbbreviation(week.weekStart),
                formattedValue = formatVolume(week.totalVolume),
            )
        },
        change = state.changeRatio?.let { ratio ->
            ChartChange(
                // Half a percentage point rather than the default hundredth, because that is
                // what `formatPercentChange` rounds to: at the default a ratio of 0.008 draws
                // as `1%` with no arrow while announcing itself as "unchanged", which is the
                // chip and its description disagreeing.
                direction = ChangeDirection.of(ratio, tolerance = PERCENT_ROUNDING),
                value = formatPercentChange(ratio),
            )
        },
        modifier = modifier,
    )
}

/**
 * The smallest change the chip draws an arrow for: half a percentage point.
 *
 * Tied to [formatPercentChange]'s rounding rather than picked. Anything at or above this
 * renders as `1%` or more, and a magnitude on the chip with no arrow beside it — announced as
 * "unchanged" — is the drawing and the description saying two different things.
 */
private const val PERCENT_ROUNDING = 0.005
