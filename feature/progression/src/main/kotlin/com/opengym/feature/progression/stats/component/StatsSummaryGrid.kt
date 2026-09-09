package com.opengym.feature.progression.stats.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.component.StatTile
import com.opengym.core.ui.format.formatAsLength
import com.opengym.core.ui.format.formatAsTotal
import com.opengym.core.ui.format.formatCount
import com.opengym.core.ui.format.formatMagnitude
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.stats.SummaryState
import kotlin.time.Duration.Companion.milliseconds

/**
 * The four figures under the hero: how often, how consistently, how well, how long.
 *
 * Two columns rather than four, for the reason `SessionStatGrid` gives: a 360dp screen minus
 * its margins leaves each tile 160dp, and a five-digit figure in the large numeric role needs
 * most of it.
 *
 * ### Every tile carries a caption, and three of them are comparisons
 *
 * `StatTile`'s `unit` slot is the line under the figure, and here it is what the figure is
 * *against* rather than what it is measured in — `+0.4 vs prev`, `across 5 ex.`, `52m /
 * session`. A bare count over a ninety-day window says almost nothing on its own: seven
 * records is a good quarter or a lucky fortnight depending on how many exercises they came
 * from.
 *
 * ### None of them is accented
 *
 * The accent on this screen is spent above, on the change chip, and below, on the muscles
 * worked directly. A grid where every tile is a result would make none of them one — see
 * `StatTile`'s `accented`.
 */
@Composable
fun StatsSummaryGrid(
    state: SummaryState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
            StatTile(
                label = stringResource(R.string.stats_sessions_label),
                value = formatCount(state.sessions),
                unit = stringResource(R.string.stats_sessions_caption),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_frequency_label),
                value = formatMagnitude(state.averagePerWeek, RATE_DIGITS),
                unit = state.averageChange.frequencyCaption(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
            StatTile(
                label = stringResource(R.string.stats_records_label),
                value = formatCount(state.records.records),
                unit = pluralStringResource(
                    R.plurals.stats_records_caption,
                    state.records.exercises,
                    state.records.exercises,
                ),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.stats_time_label),
                value = state.trainingMillis.milliseconds.formatAsTotal(),
                unit = state.averageSessionCaption(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * `+0.4 vs prev`, `-0.2 vs prev`, or "this period" when there is nothing to compare against.
 *
 * The sign comes from a string resource rather than from the formatter, so a translation can
 * move it — see [formatMagnitude]. A change of zero takes the `+` branch and reads `+0 vs
 * prev`, which is the honest rendering of a period that held its ground: the alternative is a
 * third string saying "unchanged" for a figure that is drawn to a tenth of a day.
 */
@Composable
private fun Double?.frequencyCaption(): String =
    when {
        this == null -> stringResource(R.string.stats_caption_this_period)
        this < 0 -> stringResource(R.string.stats_frequency_caption_down, formatMagnitude(this, RATE_DIGITS))
        else -> stringResource(R.string.stats_frequency_caption_up, formatMagnitude(this, RATE_DIGITS))
    }

/**
 * `52m / session`, or nothing to average over.
 *
 * Divided here rather than in the ViewModel because it is the *caption* of a tile, derived
 * from two figures the state already carries — moving it up would put a third field in
 * [SummaryState] that is a function of the other two and could disagree with them.
 *
 * ### One session's length, not a period's total
 *
 * [formatAsLength] rather than the [formatAsTotal] the figure above it wears. The two roles
 * differ past the hour: a total drops its minutes because `32h 47m` claims a precision nobody
 * reads over a quarter, and an *average session* that did the same would collapse everything
 * from 1h00m to 1h59m into a single `1h`. A lifter whose sessions crept from an hour to an hour
 * and a half would watch this caption not move.
 */
@Composable
private fun SummaryState.averageSessionCaption(): String =
    if (sessions <= 0) {
        stringResource(R.string.stats_caption_this_period)
    } else {
        stringResource(
            R.string.stats_time_caption,
            (trainingMillis / sessions).milliseconds.formatAsLength(),
        )
    }

/** Days per week is read to a tenth of a day: `3.2`, and `+0.4` against the period before. */
private const val RATE_DIGITS = 1
